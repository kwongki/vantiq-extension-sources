package io.vantiq.extsrc.senseLinkConnector;

import io.vantiq.extjsdk.ExtensionWebSocketClient;

import java.util.TimerTask;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.*;

public class SenseLinkPingTask extends TimerTask{
    final Logger log;
    private String sourceName = "";

    ExtensionWebSocketClient client = null;

    public SenseLinkPingTask(String sourceName, ExtensionWebSocketClient client) {
        log = LoggerFactory.getLogger(this.getClass().getCanonicalName() + '#' + sourceName);
        this.sourceName = sourceName;
        this.client = client;
    }

    public void run() {
        LocalDateTime currentTime = LocalDateTime.now();
        log.info("Ping KeepAlive Message!!!");
        JSONObject pingJson = new JSONObject("{\"message\": \"Ping Alive!\", \"timeStamp\" : \""+ currentTime.toString() +"\"}");
        client.sendNotification(pingJson.toMap());
    }

}
