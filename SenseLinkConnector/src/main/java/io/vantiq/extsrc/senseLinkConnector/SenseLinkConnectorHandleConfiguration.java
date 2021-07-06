package io.vantiq.extsrc.senseLinkConnector;

import io.vantiq.extjsdk.ExtensionServiceMessage;
import io.vantiq.extjsdk.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SenseLinkConnectorHandleConfiguration extends Handler<ExtensionServiceMessage> {
    Logger log;
    String sourceName;
    SenseLinkConnectorCore core;

    boolean configComplete = false; // Not currently used

    // Constants for getting config options
    private static final String CONFIG = "config";
    private static final String SENSELINK_CONFIG = "senseLinkConfig";
    private static final String GENERAL = "general";

    public SenseLinkConnectorHandleConfiguration(SenseLinkConnectorCore core) {
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
        if (!(config.get(SENSELINK_CONFIG) instanceof Map)) {
            log.error("Configuration failed. Configuration must contain 'senseLinkConfig' field.");
            failConfig();
            return;
        }
        senselinkConfig = (Map) config.get(SENSELINK_CONFIG);

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
        setupSenseLinkConnector(general);
    }

    public void setupSenseLinkConnector(Map generalConfig) {
         /**
         * CKK: Initate SenseLink side connectivity
         * 1. Initiate HTTP POST query to SenseLink to confirm connectivity
         * 2. Initiate HTTP Listener for POST from SenseLink for events 
         * 
         */
        if (generalConfig.get(SenseLinkConnectorCore.SENSELINK_APPKEY) != null && 
            generalConfig.get(SenseLinkConnectorCore.SENSELINK_APPSECRET) != null &&
            generalConfig.get(SenseLinkConnectorCore.SENSELINK_BASEURL) != null &&
            generalConfig.get(SenseLinkConnectorCore.LOCAL_EVENT_LISTENER_URL) != null && 
            generalConfig.get(SenseLinkConnectorCore.LOCAL_EVENT_LISTENER_PORT) != null) {

            String _appKey = (String) generalConfig.get(SenseLinkConnectorCore.SENSELINK_APPKEY);
            String _appSecret = (String) generalConfig.get(SenseLinkConnectorCore.SENSELINK_APPSECRET);
            String _url = (String) generalConfig.get(SenseLinkConnectorCore.SENSELINK_BASEURL);
            String _localServer = (String) generalConfig.get(SenseLinkConnectorCore.LOCAL_EVENT_LISTENER_URL);
            int _localServerPort = (int) generalConfig.get(SenseLinkConnectorCore.LOCAL_EVENT_LISTENER_PORT);
            String _localServerPath = (generalConfig.get(SenseLinkConnectorCore.LOCAL_EVENT_LISTENER_PATH) != null) ? (String) generalConfig.get(SenseLinkConnectorCore.LOCAL_EVENT_LISTENER_PATH) : "";

            Map<String, Object> resultsMap = core.checkSenseLinkVersion(_url, _appKey, _appSecret);
            if ( ((Integer)(resultsMap.get("code")) == 200) && (((String)(resultsMap.get("message"))).equals("OK")) ) {
                log.info("SenseLink is OK!");
            }
            //log.info(resultsMap.keySet().toString());

            core.startListenerServer(_localServer, _localServerPort, _localServerPath);

        } else {
            log.error("No App Key or App Secret for SenseLink found!");
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
     * Closes the source {@link SenseLinkConnectorCore} and marks the configuration as completed. The source will
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
