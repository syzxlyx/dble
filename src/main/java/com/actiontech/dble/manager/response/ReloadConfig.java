/*
* Copyright (C) 2016-2017 ActionTech.
* based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
* License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
*/
package com.actiontech.dble.manager.response;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.BackendConnection;
import com.actiontech.dble.net.FrontendConnection;
import com.actiontech.dble.server.ServerConnection;
import com.actiontech.dble.route.RouteResultsetNode;
import com.actiontech.dble.backend.datasource.PhysicalDBNode;
import com.actiontech.dble.backend.datasource.PhysicalDBPool;
import com.actiontech.dble.backend.datasource.PhysicalDatasource;
import com.actiontech.dble.backend.mysql.nio.MySQLConnection;
import com.actiontech.dble.config.ConfigInitializer;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.config.ServerConfig;
import com.actiontech.dble.config.loader.zkprocess.comm.ZkConfig;
import com.actiontech.dble.config.loader.zkprocess.comm.ZkParamCfg;
import com.actiontech.dble.config.loader.zkprocess.xmltozk.XmltoZkMain;
import com.actiontech.dble.config.loader.zkprocess.zktoxml.listen.ConfigStatusListener;
import com.actiontech.dble.config.model.ERTable;
import com.actiontech.dble.config.model.FirewallConfig;
import com.actiontech.dble.config.model.SchemaConfig;
import com.actiontech.dble.config.model.UserConfig;
import com.actiontech.dble.config.util.DnPropertyUtil;
import com.actiontech.dble.log.AlarmAppender;
import com.actiontech.dble.manager.ManagerConnection;
import com.actiontech.dble.net.NIOProcessor;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.server.variables.SystemVariables;
import com.actiontech.dble.server.variables.VarsExtractorHandler;
import com.actiontech.dble.util.KVPathUtil;
import com.actiontech.dble.util.ZKUtils;
import com.actiontech.dble.route.parser.ManagerParseConfig;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author mycat
 * @author zhuam
 */
public final class ReloadConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadConfig.class);

    private static final int OTHER = -1;
    private static final int CONFIG = 1;
    private static final int CONFIG_ALL = 2;

    private ReloadConfig() {
    }

    public static void execute(ManagerConnection c, String stmt, int offset) {
        ManagerParseConfig parser = new ManagerParseConfig();
        int rs = parser.parse(stmt, offset);
        switch (rs) {
            case ManagerParseConfig.CONFIG:
                ReloadConfig.execute(c, false, 0);
                break;
            case ManagerParseConfig.CONFIG_ALL:
                ReloadConfig.execute(c, true, parser.getMode());
                break;
            default:
                c.writeErrMessage(ErrorCode.ER_YES, "Unsupported statement");
        }
    }

    private static void execute(ManagerConnection c, final boolean loadAll, final int loadAllMode) {
        // reload @@config_all check the last old connections
        if (loadAll && (!NIOProcessor.BACKENDS_OLD.isEmpty()) && ((loadAllMode & ManagerParseConfig.OPTF_MODE) == 0)) {
            c.writeErrMessage(ErrorCode.ER_YES, "The before reload @@config_all has an unfinished db transaction, please try again later.");
            return;
        }

        if (DbleServer.getInstance().isUseZK()) {
            CuratorFramework zkConn = ZKUtils.getConnection();
            InterProcessMutex distributeLock = new InterProcessMutex(zkConn, KVPathUtil.getConfChangeLockPath());
            try {
                if (!distributeLock.acquire(100, TimeUnit.MILLISECONDS)) {
                    c.writeErrMessage(ErrorCode.ER_YES, "Other instance is reloading/rolling back, please try again later.");
                    return;
                }

                try {
                    final ReentrantLock lock = DbleServer.getInstance().getConfig().getLock();
                    lock.lock();
                    try {
                        load(loadAll, loadAllMode);
                        //tell zk this instance has prepared
                        ZKUtils.createTempNode(KVPathUtil.getConfStatusPath(), ZkConfig.getInstance().getValue(ZkParamCfg.ZK_CFG_MYID),
                                               ConfigStatusListener.SUCCESS.getBytes(StandardCharsets.UTF_8));
                        XmltoZkMain.writeConfFileToZK(loadAll, loadAllMode);

                        //check all session waiting status
                        List<String> preparedList = zkConn.getChildren().forPath(KVPathUtil.getConfStatusPath());
                        List<String> onlineList = zkConn.getChildren().forPath(KVPathUtil.getOnlinePath());
                        // TODO: While waiting, a new instance of MyCat is upping and working.
                        while (preparedList.size() < onlineList.size()) {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
                            onlineList = zkConn.getChildren().forPath(KVPathUtil.getOnlinePath());
                            preparedList = zkConn.getChildren().forPath(KVPathUtil.getConfStatusPath());
                        }

                        StringBuilder sbErrorInfo = new StringBuilder();
                        for (String child : preparedList) {
                            String childPath = ZKPaths.makePath(KVPathUtil.getConfStatusPath(), child);
                            byte[] errorInfo = zkConn.getData().forPath(childPath);
                            if (!ConfigStatusListener.SUCCESS.equals(new String(errorInfo, StandardCharsets.UTF_8))) {
                                sbErrorInfo.append(child).append(":");
                                sbErrorInfo.append(new String(errorInfo, StandardCharsets.UTF_8));
                                sbErrorInfo.append(";");
                            }
                            zkConn.delete().forPath(ZKPaths.makePath(KVPathUtil.getConfStatusPath(), child));
                        }

                        if (sbErrorInfo.length() == 0) {
                            writeOKResult(c);
                        } else {
                            writeErrorResult(c, sbErrorInfo.toString());
                        }
                    } catch (Exception e) {
                        LOGGER.warn("reload config failure", e);
                        writeErrorResult(c, e.getMessage() == null ? e.toString() : e.getMessage());
                    } finally {
                        lock.unlock();
                    }
                } finally {
                    distributeLock.release();
                }
            } catch (Exception e) {
                LOGGER.info("reload config failure", e);
                writeErrorResult(c, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        } else {
            final ReentrantLock lock = DbleServer.getInstance().getConfig().getLock();
            lock.lock();
            try {
                try {
                    load(loadAll, loadAllMode);
                    writeOKResult(c);
                } catch (Exception e) {
                    LOGGER.info("reload error", e);
                    writeErrorResult(c, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private static void load(final boolean loadAll, final int loadAllMode) throws Exception {
        if (loadAll) {
            reloadAll(loadAllMode);
        } else {
            reload();
        }
    }

    private static void writeOKResult(ManagerConnection c) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("send ok package to client " + String.valueOf(c));
        }

        OkPacket ok = new OkPacket();
        ok.setPacketId(1);
        ok.setAffectedRows(1);
        ok.setServerStatus(2);
        ok.setMessage("Reload config success".getBytes());
        ok.write(c);
    }

    private static void writeErrorResult(ManagerConnection c, String errorMsg) {
        String sb = "Reload config failure.The reason is " + errorMsg;
        LOGGER.info(sb + "." + String.valueOf(c));
        c.writeErrMessage(ErrorCode.ER_YES, sb);
    }

    public static void reloadAll(final int loadAllMode) throws Exception {
        /*
         *  1 load new conf
         *  1.1 ConfigInitializer init adn check itself
         *  1.2 DataNode/DataHost test connection
         */
        ConfigInitializer loader;
        try {
            loader = new ConfigInitializer(true, DbleServer.getInstance().getSystemVariables().isLowerCaseTableNames());
        } catch (Exception e) {
            throw new Exception(e);
        }
        Map<String, UserConfig> newUsers = loader.getUsers();
        Map<String, SchemaConfig> newSchemas = loader.getSchemas();
        Map<String, PhysicalDBNode> newDataNodes = loader.getDataNodes();
        Map<String, PhysicalDBPool> newDataHosts = loader.getDataHosts();
        Map<ERTable, Set<ERTable>> newErRelations = loader.getErRelations();
        FirewallConfig newFirewall = loader.getFirewall();

        SystemVariables newSystemVariables = DbleServer.getInstance().getSystemVariables();
        if (!loader.isDataHostWithoutWH()) {
            VarsExtractorHandler handler = new VarsExtractorHandler(newDataNodes);
            newSystemVariables = handler.execute();
            ConfigInitializer confInit = new ConfigInitializer(newSystemVariables.isLowerCaseTableNames());
            newUsers = confInit.getUsers();
            newSchemas = confInit.getSchemas();
            newDataNodes = confInit.getDataNodes();
            newErRelations = confInit.getErRelations();
            newFirewall = confInit.getFirewall();
            newDataHosts = confInit.getDataHosts();
        }

        if ((loadAllMode & ManagerParseConfig.OPTT_MODE) != 0) {
            try {
                loader.testConnection(false);
            } catch (Exception e) {
                throw new Exception(e);
            }
        }

        /*
         *  2 transform
         *  2.1 old dataSource continue to work
         *  2.2 init the new dataSource
         *  2.3 transform
         *  2.4 put the old connection into a queue
         */
        ServerConfig config = DbleServer.getInstance().getConfig();

        /* 2.1 do nothing */
        boolean isReloadStatusOK = true;

        /* 2.2 init the new dataSource */
        for (PhysicalDBPool dbPool : newDataHosts.values()) {
            String hostName = dbPool.getHostName();
            // set schemas
            ArrayList<String> dnSchemas = new ArrayList<>(30);
            for (PhysicalDBNode dn : newDataNodes.values()) {
                if (dn.getDbPool().getHostName().equals(hostName)) {
                    dnSchemas.add(dn.getDatabase());
                }
            }
            dbPool.setSchemas(dnSchemas.toArray(new String[dnSchemas.size()]));

            // get data host
            String dnIndex = DnPropertyUtil.loadDnIndexProps().getProperty(dbPool.getHostName(), "0");
            if (!"0".equals(dnIndex)) {
                LOGGER.info("init data host: " + dbPool.getHostName() + " to use datasource index:" + dnIndex);
            }

            dbPool.init(Integer.parseInt(dnIndex));
            if (!dbPool.isInitSuccess()) {
                isReloadStatusOK = false;
                break;
            }
        }

        if (isReloadStatusOK) {
            /* 2.3 apply new conf */
            config.reload(newUsers, newSchemas, newDataNodes, newDataHosts, newErRelations, newFirewall,
                    newSystemVariables, loader.isDataHostWithoutWH(), true);

            recycleOldBackendConnections(config, ((loadAllMode & ManagerParseConfig.OPTF_MODE) != 0));
            AlarmAppender.refreshConfig();
        } else {
            // INIT FAILED
            LOGGER.info("reload failed, clear previously created data sources ");
            for (PhysicalDBPool dbPool : newDataHosts.values()) {
                dbPool.clearDataSources("reload config");
                dbPool.stopHeartbeat();
            }
            throw new Exception("Init DbPool failed");
        }
    }

    private static void findAndcloseFrontCon(BackendConnection con) {
        if (con instanceof MySQLConnection) {
            MySQLConnection mcon1 = (MySQLConnection) con;
            for (NIOProcessor processor : DbleServer.getInstance().getProcessors()) {
                for (FrontendConnection fcon : processor.getFrontends().values()) {
                    if (fcon instanceof ServerConnection) {
                        ServerConnection scon = (ServerConnection) fcon;
                        Map<RouteResultsetNode, BackendConnection> bons = scon.getSession2().getTargetMap();
                        for (BackendConnection bcon : bons.values()) {
                            if (bcon instanceof MySQLConnection) {
                                MySQLConnection mcon2 = (MySQLConnection) bcon;
                                if (mcon1 == mcon2) {
                                    scon.killAndClose("reload config all");
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void recycleOldBackendConnections(ServerConfig config, boolean closeFrontCon) {
        /* 2.4 put the old connection into a queue */
        Map<String, PhysicalDBPool> oldDataHosts = config.getBackupDataHosts();
        for (PhysicalDBPool dbPool : oldDataHosts.values()) {
            dbPool.stopHeartbeat();
            for (PhysicalDatasource ds : dbPool.getAllDataSources()) {
                for (NIOProcessor processor : DbleServer.getInstance().getProcessors()) {
                    for (BackendConnection con : processor.getBackends().values()) {
                        if (con instanceof MySQLConnection) {
                            MySQLConnection mysqlCon = (MySQLConnection) con;
                            if (mysqlCon.getPool() == ds) {
                                if (con.isBorrowed()) {
                                    if (closeFrontCon) {
                                        findAndcloseFrontCon(con);
                                    } else {
                                        NIOProcessor.BACKENDS_OLD.add(con);
                                    }
                                } else {
                                    con.close("old idle conn for reload");
                                }
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info("the size of old backend connection to be recycled is: " + NIOProcessor.BACKENDS_OLD.size());
    }

    public static void reload() throws Exception {
        /* 1 load new conf, ConfigInitializer will check itself */
        ConfigInitializer loader;
        try {
            loader = new ConfigInitializer(false, DbleServer.getInstance().getSystemVariables().isLowerCaseTableNames());
        } catch (Exception e) {
            throw new Exception(e);
        }
        Map<String, UserConfig> users = loader.getUsers();
        Map<String, SchemaConfig> schemas = loader.getSchemas();
        Map<String, PhysicalDBNode> dataNodes = loader.getDataNodes();
        Map<String, PhysicalDBPool> dataHosts = loader.getDataHosts();
        Map<ERTable, Set<ERTable>> erRelations = loader.getErRelations();
        FirewallConfig firewall = loader.getFirewall();

        /* 2 apply the new conf */
        DbleServer.getInstance().getConfig().reload(users, schemas, dataNodes, dataHosts, erRelations, firewall,
                                                    DbleServer.getInstance().getSystemVariables(), loader.isDataHostWithoutWH(), false);
    }

}
