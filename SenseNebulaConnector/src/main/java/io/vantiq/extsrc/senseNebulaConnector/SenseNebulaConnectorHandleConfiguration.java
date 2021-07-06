package io.vantiq.extsrc.senseNebulaConnector;

import io.vantiq.extjsdk.ExtensionServiceMessage;
import io.vantiq.extjsdk.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SenseNebulaConnectorHandleConfiguration extends Handler<ExtensionServiceMessage> {
    Logger log;
    String sourceName;
    SenseNebulaConnectorCore core;

    boolean configComplete = false; // Not currently used

    // Constants for getting config options
    private static final String CONFIG = "config";
    private static final String SENSE_NEBULA_CONFIG = "senseNebulaConfig";
    private static final String GENERAL = "general";

    public SenseNebulaConnectorHandleConfiguration(SenseNebulaConnectorCore core) {
        this.core = core;
        this.sourceName = core.getSourceName();
        log = LoggerFactory.getLogger(this.getClass().getCanonicalName() + "#" + sourceName);
    }

    @Override
    public void handleMessage(ExtensionServiceMessage message) {
        Map<String, Object> configObject = (Map) message.getObject();
        Map<String, Object> config;
        Map<String, Object> senselinkConfig;
        Map<String, Object> general;

        // Obtain entire config from the message object
        if (!(configObject.get(CONFIG) instanceof Map)) {
            log.error("Configuration failed. No configuration suitable for SenseLink Connector.");
            failConfig();
            return;
        }
        config = (Map) configObject.get(CONFIG);

        // Retrieve the testConfig and the vantiq config
        if (!(config.get(SENSE_NEBULA_CONFIG) instanceof Map)) {
            log.error("Configuration failed. Configuration must contain 'senseNebulaConfig' field.");
            failConfig();
            return;
        }
        senselinkConfig = (Map) config.get(SENSE_NEBULA_CONFIG);

        // Get the general options from the testConfig
        if (!(senselinkConfig.get(GENERAL) instanceof Map)) {
            log.error("Configuration failed. No general options specified.");
            failConfig();
            return;
        }
        general = (Map) senselinkConfig.get(GENERAL);

        // Call method to setup the connector with the source configuration
        /**
         * CKK: From here, grab the Config properties from VANTIQ to setup
         * 1. Wait for POST connection from SenseLink
         */
        setupSenseNebulaConnector(general);
    }

    public void setupSenseNebulaConnector(Map generalConfig) {
         /**
         * CKK: Initate SenseNebula side connectivity
         * 1. Login --> returns data value to be used as session key
         * 2. Get version information 
         * 
         */
        if (generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_BASEURL) != null && 
            generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_USER_NAME) != null &&
            generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_PASSWORD) != null &&
            generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_FACEDB) != null) {

            String _url = (String) generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_BASEURL);
            String _userName = (String) generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_USER_NAME);
            String _pwd = (String) generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_PASSWORD);
            String _faceDB = (String) generalConfig.get(SenseNebulaConnectorCore.SENSE_NEBULA_FACEDB);

            Map<String, Object> resultsMap = core.loginSenseNebula(_url, _userName, _pwd, _faceDB);
            if ( (resultsMap != null) && ((Integer)(resultsMap.get("code")) == 200) && (((String)(resultsMap.get("message"))).equals("OK")) ) {
                log.info("SenseNebula Login is OK!");
            }
            else {
                log.error("SenseNebula Login failed!");
                failConfig();
                return;
            }
            //log.info(resultsMap.keySet().toString());

            core.startNebulaWSConnection();

        } else {
            log.error("No username, password, senseNebula_URL or FR_dbName in config found!");
            failConfig();
            return;
        }

    }

    /**
     * Helper method to check that list elements are all non-empty Strings.
     * @param list List of objects to check
     * @return
     */
    public static boolean checkListValues(List list) {
        for (Object listElem : list) {
            if (!(listElem instanceof String) || ((String) listElem).isEmpty()) {
                return false;
            }
        }
        return true;
    }


    /**
     * Closes the source {@link SenseNebulaConnectorCore} and marks the configuration as completed. The source will
     * be reactivated when the source reconnects, due either to a Reconnect message (likely created by an update to the
     * configuration document) or to the WebSocket connection crashing momentarily.
     */
    private void failConfig() {
        core.close();
        configComplete = true;
    }

    /**
     * Returns whether the configuration handler has completed. Necessary since the sourceConnectionFuture is completed
     * before the configuration can complete, so a program may need to wait before using configured resources.
     * @return  true when the configuration has completed (successfully or not), false otherwise
     */
    public boolean isComplete() {
        return configComplete;
    }
}
