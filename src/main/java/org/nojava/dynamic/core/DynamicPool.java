package org.nojava.dynamic.core;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.nojava.dynamic.config.ConnectionInfo;
import org.nojava.dynamic.config.DynamicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * User: cailei
 * Date: 15/7/13
 * Time: 上午11:02
 */
public class DynamicPool {

    /**
     * Logger class
     */
    private static final Logger logger = LoggerFactory.getLogger(DynamicPool.class);

    private ConcurrentHashMap<String, BlockingQueue<ConnectionHandle>> connectionHandleQueue = new ConcurrentHashMap<>();


    private DynamicConfig dynamicConfig;

    private Map<String, ConnectionInfo> connectionInfoConfig = Maps.newHashMap();


    private ConcurrentHashMap<String, ReentrantLock> tenantLock = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, AtomicInteger> createdConnections = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, AtomicInteger> queueConnections = new ConcurrentHashMap<>();


    //初始化标记
    private List<String> initTenantId = Lists.newLinkedList();


    public ConcurrentHashMap<String, BlockingQueue<ConnectionHandle>> getConnectionHandleQueue() {
        return connectionHandleQueue;
    }

    public ConcurrentHashMap<String, AtomicInteger> getCreatedConnections() {
        return createdConnections;
    }


    public ConcurrentHashMap<String, AtomicInteger> getQueueConnections() {
        return queueConnections;
    }


    public Connection getConnection(String tenantId) throws Exception {
        initCount(tenantId);
        return createConnectionOrPoll(tenantId);
    }


    public DynamicPool(DynamicConfig dynamicConfig, Map<String, ConnectionInfo> connectionInfoConfig) {
        this.dynamicConfig = dynamicConfig;
        Thread singleClose = new Thread(new CloseThreadMonitor(this));
        singleClose.start();
        Thread singleCollectInfo = new Thread(new CollectThreadMonitor(this));
        singleCollectInfo.start();
        this.connectionInfoConfig = connectionInfoConfig;
    }


    public DynamicConfig getDynamicConfig() {
        return dynamicConfig;
    }

    public void setDynamicConfig(DynamicConfig dynamicConfig) {
        this.dynamicConfig = dynamicConfig;
    }

    public synchronized void initCount(String tenantId) {
        if (!initTenantId.contains(tenantId)) {
            initTenantId.add(tenantId);
            queueConnections.put(tenantId, new AtomicInteger(0));
            createdConnections.put(tenantId, new AtomicInteger(0));
            connectionHandleQueue.put(tenantId, new LinkedBlockingQueue<>());
            tenantLock.put(tenantId, new ReentrantLock());
        }
    }

    private Connection createConnectionOrPoll(String tenantId) throws Exception {

        try {
            tenantLock.get(tenantId).lock();
            //未达到最大数量，并且无闲置数量，创建
            if (createdConnections.get(tenantId).get() < dynamicConfig.getMaxConnction() && queueConnections.get(tenantId).get() == 0) {
                return getNewConnection(tenantId);
            }
            //从队列中取，不创建,达到db最大连接数
            ConnectionHandle connectionHandle = connectionHandleQueue.get(tenantId).poll(dynamicConfig.getWaitTime(), TimeUnit.SECONDS);
            queueConnections.get(tenantId).getAndAdd(-1);
            connectionHandle.setBorrowTime(System.currentTimeMillis());
            connectionHandle.setReturnTime(-1l);
            return connectionHandle;
        } catch (Exception e) {
            logger.error("租户：" + tenantId, e);
            throw e;
        } finally {
            tenantLock.get(tenantId).unlock();
        }

    }

    private Connection getNewConnection(String tenantId) throws Exception {
        ConnectionHandle connectionHandle = buildConnection(connectionInfoConfig.get(tenantId));
        createdConnections.get(tenantId).getAndAdd(1);
        connectionHandle.setBorrowTime(System.currentTimeMillis());
        connectionHandle.setReturnTime(-1l);
        return connectionHandle;
    }


    private ConnectionHandle buildConnection(final ConnectionInfo connectionInfo) throws Exception {
        Class.forName(connectionInfo.getDriverClassName());
        Connection connection;
        FutureTask<Connection> future =
                new FutureTask<Connection>(new Callable<Connection>() {//使用Callable接口作为构造参数
                    public Connection call() throws SQLException {
                        //真正的任务在这里执行，这里的返回值类型为String，可以为任意类型
                        Connection connection = DriverManager.getConnection(connectionInfo.getUrl(), connectionInfo.getUsername(), connectionInfo.getPassword());
                        return connection;
                    }
                });
        Thread thread = new Thread(future);
        thread.start();
        try {
            connection = future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("链接信息为:" + connectionInfo.toString());
            throw e;
        }

        ConnectionHandle connectionHandle = new ConnectionHandle();
        connectionHandle.setConnection(connection);
        connectionHandle.setCreateTime(System.currentTimeMillis());
        connectionHandle.setBorrowTime(-1l);
        connectionHandle.setReturnTime(-1l);
        connectionHandle.setAutoCommit(true);
        connectionHandle.setTenantId(connectionInfo.getTenantId());
        connectionHandle.setDynamicPool(this);
        return connectionHandle;
    }


    public void close(Connection connection) throws InterruptedException {
        try {
            ConnectionHandle connectionHandle = (ConnectionHandle) connection;
            logger.info("close-" + connectionHandle.getTenantId());
            connectionHandle.setReturnTime(System.currentTimeMillis());
            connectionHandle.setBorrowTime(-1l);

            connectionHandleQueue.get(connectionHandle.getTenantId())
                    .put(connectionHandle);
            queueConnections.get(connectionHandle.getTenantId()).getAndAdd(1);
        } catch (Exception e) {
            logger.error("close error", e);
            throw e;
        }

    }

    public void cleanIdleConn() {
        logger.debug("cleanIdleConn-");

        for (String tenantId : getConnectionHandleQueue().keySet()) {
            try {
                tenantLock.get(tenantId).lock();
                Set<ConnectionHandle> waitClean = new HashSet<>();
                for (ConnectionHandle connectionHandle : getConnectionHandleQueue().get(tenantId)) {
                    if (allowClose(connectionHandle.getReturnTime())) {
                        waitClean.add(connectionHandle);
                    }
                }
                for (ConnectionHandle connectionHandleClean : waitClean) {
                    try {
                        getConnectionHandleQueue().get(tenantId).remove(connectionHandleClean);
                        connectionHandleClean.getConnection().close();
                        connectionHandleClean.setConnection(null);
                        getCreatedConnections().get(tenantId).getAndAdd(-1);
                        getQueueConnections().get(tenantId).getAndAdd(-1);
                    } catch (Exception e) {
                        logger.error("回收发生异常", e);
                        e.printStackTrace();
                    }

                }
            } catch (Exception e) {
                logger.error("cleanIdleConn", e);
                throw e;
            } finally {
                tenantLock.get(tenantId).unlock();
            }
        }

    }


    private boolean allowClose(Long returnTime) {
        if (returnTime > 0l && (System.currentTimeMillis() - returnTime) > (getDynamicConfig().getIdleMaxAgeInSeconds() * 1000l)) {
            return true;
        }
        return false;
    }


}
