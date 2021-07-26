/*
 * Copyright (c) 2021 Vantiq, Inc.
 *
 * All rights reserved.
 *
 * SPDX: MIT
 */
package io.vantiq.extsrc.senseNebulaConnector;

import io.vantiq.extjsdk.ExtensionServiceMessage;
import io.vantiq.extjsdk.ExtensionWebSocketClient;
import io.vantiq.extjsdk.Handler;
//import jdk.dynalink.beans.StaticClass;
//import jdk.internal.jshell.tool.resources.l10n;
//import jdk.jfr.internal.RequestEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.spi.DateFormatProvider;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Base64;

// CKK
import java.time.Duration;
import java.time.Instant;

import okhttp3.*;
import okhttp3.ws.*;
import okio.Buffer;

import org.json.*;
import org.apache.commons.text.StringEscapeUtils;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

public class SenseNebulaConnectorCore {

    String sourceName;
    String authToken;
    String targetVantiqServer;

    private String sn_username = "";
    private String sn_password = "";
    private String sn_url = "";
    private String sn_faceDB = "";
    private int sn_faceDB_id = -1;
    private String sn_sessionKey = "";
    private String sn_websocketKey = "";
    private String sn_dev_id = "";
    private String sn_serial_id = "";
    private String sn_version = "";
    private String sn_web_version = "";
    
    private Thread keepAliveThread;

    SenseNebulaConnectorHandleConfiguration testConnectorHandleConfiguration;
    ExtensionWebSocketClient client = null;
    WebSocket nebulaWebSocket = null;

    List<String> filenames = new ArrayList<>();

    final Logger log;
    final Logger rawEventLog;
    final static int RECONNECT_INTERVAL = 5000;

    static final String ENVIRONMENT_VARIABLES = "environmentVariables";
    static final String FILENAMES = "filenames";

    static final String SENSE_NEBULA_BASEURL = "senseNebula_URL";
    static final String SENSE_NEBULA_USER_NAME = "username";
    static final String SENSE_NEBULA_PASSWORD = "password";
    static final String SENSE_NEBULA_FACEDB = "FR_dbName";

    static final String LOCAL_EVENT_LISTENER_URL = "eventListenServer";
    static final String LOCAL_EVENT_LISTENER_PORT = "eventListenPort"; 
    static final String LOCAL_EVENT_LISTENER_PATH = "eventListenServerPath";
    
    static final String SN_REST_Base = "/api/json";
    static final String SN_WS_Base = "/ws";
    static final String SN_MSG_LOGIN = "257";
    static final String SN_MSG_QUERYDB = "1028";
    static final String SN_MSG_STORE_FACE = "1051";
    static final String SN_MSG_QUERY_VERSION = "1281";
    static final String SN_MSG_GET_WS_KEY = "1286";
    static final String SN_MSG_SUBS_EVENT = "776";
    
    static final String SL_RESTEndPoint_Users = "users";
    static final String SL_RESTEndPoint_Types = "types";
    static final String SL_RESTEndPoint_Images = "images";
    static final String SL_RESTEndPoint_Groups = "groups";

    // Timer used if source is configured to poll from files
    Timer pollingTimer;
    Timer keepAliveTimer;
    Timer keepNebulaAliveTimer;

    /**
     * Stops sending messages to the source and tries to reconnect indefinitely
     */
    public final Handler<ExtensionServiceMessage> reconnectHandler = new Handler<ExtensionServiceMessage>() {
        @Override
        public void handleMessage(ExtensionServiceMessage message) {
            log.trace("Reconnect message received. Reinitializing configuration");

            if (pollingTimer != null) {
                pollingTimer.cancel();
                pollingTimer = null;
            }

            if (keepAliveTimer != null) {
                keepAliveTimer.cancel();
                keepAliveTimer = null;
            }

            // Do connector-specific stuff here
            testConnectorHandleConfiguration.configComplete = false;

            // Looping call to client.doCoreReconnection()
            Thread reconnectThread = new Thread(() -> doFullClientConnection(10));
            reconnectThread.start();
        }
    };

    /**
     * Stops sending messages to the source and tries to reconnect indefinitely
     */
    public final Handler<ExtensionWebSocketClient> closeHandler = new Handler<ExtensionWebSocketClient>() {
        @Override
        public void handleMessage(ExtensionWebSocketClient message) {
            log.trace("WebSocket closed unexpectedly. Attempting to reconnect");

            if (pollingTimer != null) {
                pollingTimer.cancel();
                pollingTimer = null;
            }

            if (keepAliveTimer != null) {
                keepAliveTimer.cancel();
                keepAliveTimer = null;
            }

            testConnectorHandleConfiguration.configComplete = false;

            // Looping call to client.initiateFullConnection()
            Thread connectThread = new Thread(() -> doFullClientConnection(10));
            connectThread.start();
        }
    };

    /**
     * Publish handler that forwards publish requests to the executePublish() method
     */
    public final Handler<ExtensionServiceMessage> publishHandler = new Handler<ExtensionServiceMessage>() {
        @Override
        public void handleMessage(ExtensionServiceMessage message) {
            log.trace("Publish request received.");
            executePublish(message);
        }
    };

    /**
     * Query handler that forwards query requests to the executeQuery() method
     */
    public final Handler<ExtensionServiceMessage> queryHandler = new Handler<ExtensionServiceMessage>() {
        @Override
        public void handleMessage(ExtensionServiceMessage message) {
            log.trace("Query request received.");
            executeQuery(message);
        }
    };

    /**
     * Creates a new SenseNebulaConnectorCore with the settings given.
     * 
     * @param sourceName         The name of the source to connect to.
     * @param authToken          The authentication token to use to connect.
     * @param targetVantiqServer The url to connect to.
     */
    public SenseNebulaConnectorCore(String sourceName, String authToken, String targetVantiqServer) {
        log = LoggerFactory.getLogger(this.getClass().getCanonicalName() + '#' + sourceName);
        rawEventLog = LoggerFactory.getLogger("RawEvents");
        this.sourceName = sourceName;
        this.authToken = authToken;
        this.targetVantiqServer = targetVantiqServer;
    }

    /**
     * Tries to connect to a source and waits up to {@code timeout} seconds before
     * failing and trying again.
     * 
     * @param timeout The maximum number of seconds to wait before assuming failure
     *                and stopping.
     */
    public void start(int timeout) {
        client = new ExtensionWebSocketClient(sourceName);
        testConnectorHandleConfiguration = new SenseNebulaConnectorHandleConfiguration(this);

        client.setConfigHandler(testConnectorHandleConfiguration);
        client.setReconnectHandler(reconnectHandler);
        client.setCloseHandler(closeHandler);
        client.setPublishHandler(publishHandler);
        client.setQueryHandler(queryHandler);
        //client.setAutoReconnect(true);

        // Looping call to client.InitiateFullConnection()
        doFullClientConnection(timeout);

        // Start a HTTP Listener for POST Request from SenseLink for events
    }

    /**
     * Helper method to do the work of either initiating new connection, or doing a
     * reconnect. This is done in a loop until we get a successful connection.
     * 
     * @param timeout The maximum number of seconds to wait before assuming failure
     *                and stopping.
     */
    public void doFullClientConnection(int timeout) {
        boolean sourcesSucceeded = false;
        while (!sourcesSucceeded) {
            // Either try to reconnect, or initiate a new full connection
            if (client.isOpen() && client.isAuthed()) {
                client.doCoreReconnect();
            } else {
                client.initiateFullConnection(targetVantiqServer, authToken);
            }

            // Now check the result
            sourcesSucceeded = exitIfConnectionFails(timeout);
            if (!sourcesSucceeded) {
                try {
                    Thread.sleep(RECONNECT_INTERVAL);
                } catch (InterruptedException e) {
                    log.error("An error occurred when trying to sleep the current thread. Error Message: ", e);
                }
            }
        }

        // CKK: Start Keep Alive Message Thread to VANTIQ
        restartKeepAliveTimer();
        restartKeepNebulaLoginAliveTimer();
    }

    /**
     * CKK: Function to restart scheduled task for ping keep alive.
     */
    public void restartKeepAliveTimer() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }

        keepAliveTimer = new Timer("Nebula WS pingTimer");

        TimerTask aliveTask = new TimerTask() {
            Instant startTime = null; // to keep track of time for ping message 

            @Override
            public void run() {
                /**
                LocalDateTime currentTime = LocalDateTime.now();
                log.info("Ping KeepAlive Message!!!");
                String jsonObjectString = "{\"timeStamp\" : \""+ currentTime.format(DateTimeFormatter.ISO_DATE_TIME) +"\", \"messageType\": \"ping\", \"data\" : {";
                jsonObjectString += "\"device_id\" : \"" + sn_dev_id + "\",";
                jsonObjectString += "\"serial_id\" : \"" + sn_serial_id + "\",";
                jsonObjectString += "\"version\" : \"" + sn_version + "\",";
                jsonObjectString += "\"web_version\" : \"" + sn_web_version + "\",";
                jsonObjectString += "\"type\" : \"SenseNebula\"";
                jsonObjectString += "}}";
                JSONObject pingJson = new JSONObject(jsonObjectString);
                client.sendNotification(pingJson.toMap());
                **/
                if (nebulaWebSocket != null) {
                    try {
                        Buffer pingBuf = new Buffer();

                        if (startTime == null) {
                            startTime = Instant.now();
                            pingBuf.writeUtf8("Ping from Vantiq Connector --> Nebula ... " + startTime.toString());
                        }
                        else {
                            Instant currentInstant = Instant.now();
                            Duration interval = Duration.between(startTime, currentInstant);

                            //every one hour send a timestamp message as ping, else send empty ping message
                            if (interval.getSeconds() >= 3600) {
                                startTime = currentInstant;
                                pingBuf.writeUtf8("Ping from Vantiq Connector --> Nebula ... " + currentInstant.toString());
                            } else {
                                pingBuf.writeUtf8("");
                            }
                        }
                        //log.info("Ping Nebula!!!");
                        
                        //pingBuf.writeUtf8("Ping from Vantiq Connector!");
                        nebulaWebSocket.sendPing(pingBuf);
                    } catch (IOException e) {
                        log.error("Error trying to ping Nebula: ", e);
                    }
                    
                }
            }
        };

        // Every 4 minutes 40 secs
        keepAliveTimer.schedule(aliveTask, 100, 280000);
    }    
    
    private void restartKeepNebulaLoginAliveTimer() {
            if (keepNebulaAliveTimer != null) {
                keepNebulaAliveTimer.cancel();
                keepNebulaAliveTimer = null;
            }

            keepNebulaAliveTimer = new Timer("nebula_REST_API_PingTimer");
            TimerTask aliveTask = new TimerTask() {
                @Override
                public void run() {
                    // Just do something: Get Version Information
                    OkHttpClient client = new OkHttpClient().newBuilder().build();
                    MediaType mediaType = MediaType.parse("application/json");
                    
                    JSONObject queryVersionPayLoad = new JSONObject();
                    queryVersionPayLoad.put("msg_id", SenseNebulaConnectorCore.SN_MSG_QUERY_VERSION);
                    RequestBody queryVersionBody = RequestBody.create(mediaType, queryVersionPayLoad.toString());

                    Request request2 = new Request.Builder()
                        .url(sn_url + SenseNebulaConnectorCore.SN_REST_Base)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("sessionid", sn_sessionKey)
                        .method("POST", queryVersionBody)
                        .build();

                    try {
                        Response response2 = client.newCall(request2).execute();
                        JSONObject jsonObj2 = new JSONObject(response2.body().string());
                        response2.close();

                        if ((jsonObj2.optInt("code") == 0) && (jsonObj2.optJSONObject("data") != null)) {
                            // OK. Do nothing
                            log.info("Nebula REST Ping!");
                        } else {
                            log.error("Cannot get version from Nebula");
                        }              
                    } catch (IOException e2) {
                        log.error("Error getting version info from Nebula: ", e2);
                    }
                }
            };

            // Once every 25 minutes
            keepNebulaAliveTimer.schedule(aliveTask, 2000, 1500000);
    }

    /**
     * CKK: 02-06-2021
     * Method to login to Nebula and get a session key.
     * It will also set the url, username, password, sessionkey, websocketkey, faceDB, faceDB_id for this class
     * 1. Login --> session key
     * 2. Query version information --> device_id, serial_id, version, web_version
     * 3. Query Portrait database --> faceDB_id
     * 4. Get Websocket Subscription Key --> websocket key
     * @param url
     * @param username
     * @param password
     * @param faceDB
     * @return
     */
    public Map loginSenseNebula(String url, String username, String password, String faceDB) {

        OkHttpClient client = new OkHttpClient().newBuilder().build();
        MediaType mediaType = MediaType.parse("application/json");

        // Step 1: Login to Nebula
        JSONObject loginPayLoad = new JSONObject();
        loginPayLoad.put("msg_id", SenseNebulaConnectorCore.SN_MSG_LOGIN);
        loginPayLoad.put("user_name", username);
        loginPayLoad.put("user_pwd", password);
        RequestBody loginBody = RequestBody.create(mediaType, loginPayLoad.toString());

        Request request = new Request.Builder()
            .url(url + SenseNebulaConnectorCore.SN_REST_Base)
            .addHeader("Content-Type", "application/json")
            .method("POST", loginBody)
            .build();

        try {
            Response response = client.newCall(request).execute();
            //log.info("Response = " + response.body().string());
            JSONObject jsonObj = new JSONObject(response.body().string());
            //log.info("JSON Object = " + jsonObj.toString());
            response.close();

            if ((jsonObj.optInt("code") == 0) && (jsonObj.optString("data") != null)) {
                String _sessionId = jsonObj.optString("data");
                if (_sessionId != "") {
                    // Save first set of global variables since Login is working
                    this.sn_sessionKey = _sessionId;
                    this.sn_url = url;
                    this.sn_username = username;
                    this.sn_password = password;
                    log.info("Login in OK! ("+ this.sn_sessionKey +")");

                    // Step 2: Get Version Information
                    JSONObject queryVersionPayLoad = new JSONObject();
                    queryVersionPayLoad.put("msg_id", SenseNebulaConnectorCore.SN_MSG_QUERY_VERSION);
                    RequestBody queryVersionBody = RequestBody.create(mediaType, queryVersionPayLoad.toString());

                    Request request2 = new Request.Builder()
                        .url(this.sn_url + SenseNebulaConnectorCore.SN_REST_Base)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("sessionid", this.sn_sessionKey)
                        .method("POST", queryVersionBody)
                        .build();

                    try {
                        Response response2 = client.newCall(request2).execute();
                        JSONObject jsonObj2 = new JSONObject(response2.body().string());
                        response2.close();

                        if ((jsonObj2.optInt("code") == 0) && (jsonObj2.optJSONObject("data") != null)) {
                            // Save 2nd set of global variables
                            JSONObject dataObj = jsonObj2.optJSONObject("data");
                            this.sn_dev_id = dataObj.optString("device_id");
                            this.sn_serial_id = dataObj.optString("serial_id");
                            this.sn_version = dataObj.optString("version");
                            this.sn_web_version = dataObj.optString("web_version");
                        } else {
                            log.error("Did not get version information from Nebula");
                            return null;
                        }              
                    } catch (IOException e2) {
                        log.error("Error getting version info from Nebula: ", e2);
                    }

                    // Step 3: Query Portrait Database
                    JSONObject queryDBPayLoad = new JSONObject();
                    queryDBPayLoad.put("msg_id", SenseNebulaConnectorCore.SN_MSG_QUERYDB);
                    RequestBody queryDBBody = RequestBody.create(mediaType, queryDBPayLoad.toString());

                    Request request3 = new Request.Builder()
                        .url(this.sn_url + SenseNebulaConnectorCore.SN_REST_Base)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("sessionid", this.sn_sessionKey)
                        .method("POST", queryDBBody)
                        .build();

                    try {
                        Response response3 = client.newCall(request3).execute();
                        JSONObject jsonObj3 = new JSONObject(response3.body().string());
                        response3.close();

                        if ((jsonObj3.optInt("code") == 0) && (jsonObj3.optJSONArray("data") != null)) {
                            JSONArray dataObj = jsonObj3.optJSONArray("data");
                            for (int i=0; i<dataObj.length(); i++) {
                                if (dataObj.getJSONObject(i).optString("lib_name").equals(faceDB)) {
                                    // Save 3rd set of global variables
                                    this.sn_faceDB = faceDB;
                                    this.sn_faceDB_id = dataObj.getJSONObject(i).optInt("lib_id");
                                    break;
                                }
                            }

                        } else {
                            log.error("Did not get Portrait DB information from Nebula");
                            return null;
                        }              
                    } catch (IOException e3) {
                        log.error("Error getting Portrait DB Id from Nebula: ", e3);
                    }


                    // Step 4: Get Websocket Key
                    JSONObject getWSKeyPayLoad = new JSONObject();
                    getWSKeyPayLoad.put("msg_id", SenseNebulaConnectorCore.SN_MSG_GET_WS_KEY);
                    RequestBody wsKeyBody = RequestBody.create(mediaType, getWSKeyPayLoad.toString());

                    Request request4 = new Request.Builder()
                        .url(this.sn_url + SenseNebulaConnectorCore.SN_REST_Base)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("sessionid", this.sn_sessionKey)
                        .method("POST", wsKeyBody)
                        .build();

                    try {
                        Response response4 = client.newCall(request4).execute();
                        JSONObject jsonObj4 = new JSONObject(response4.body().string());
                        response4.close();

                        if ((jsonObj4.optInt("code") == 0) && (jsonObj4.optJSONObject("data") != null)) {
                            // Save 4th set of global variables
                            JSONObject dataObj = jsonObj4.optJSONObject("data");
                            this.sn_websocketKey = dataObj.optString("key");
                        } else {
                            log.error("Did not get WebSocket Subscription Key from Nebula");
                            return null;
                        }      

                    } catch (IOException e4) {
                        log.error("Error getting WS Key from Nebula: ", e4);
                    }

                    
                } else {
                    log.error("Did not get a valid Session Key from Nebula");
                    return null;
                }
            }

            JSONObject returnOK = new JSONObject();
            returnOK.put("code", 200);
            returnOK.put("message", "OK");

            return returnOK.toMap();
            //return response.body().string();
        } catch (IOException e) {
            log.error("Error Logging into Nebula: ", e);
        }

        return null;
    }

    /**
     * CKK: 02-06-2021
     * Method to add visitor record to Nebula platform.
     * TO BE TESTED
     * @param payload
     * @return
     */
    public Map addVisitorToSenseNebula(Map payload) {

        String errorResponsePayload = "{ \"error\": \"Error adding Visitor to SenseNebula\" }";
        JSONObject errJson = new JSONObject(errorResponsePayload);
        log.info("Inside addVisitorToSenseNebula .... ");

        // 1. Grab the image url from payload
        // 2. HTTP GET the image
        // 3. convert to base64 encoded string
        // 4. build requestPayload to Nebula --> Send!

        // -- Starting to build requestPayload ---
        JSONObject requestJSON = new JSONObject();

        requestJSON.put("msg_id", SenseNebulaConnectorCore.SN_MSG_STORE_FACE);
        requestJSON.put("lib_id", sn_faceDB_id);

        if (payload.containsKey("name") && ((String)payload.get("name") !="" )) {
            requestJSON.put("person_name", (String)payload.get("name"));
        } else {
            return errJson.toMap();
        }

        if (payload.containsKey("imageURL") && ((String)payload.get("imageURL") != "")) {
            OkHttpClient imgClient = new OkHttpClient().newBuilder().build();
            Request imgRequest = new Request.Builder()
                .url((String)payload.get("imageURL"))
                .build();

            try {
                Response imgResponse = imgClient.newCall(imgRequest).execute();
                String encodedString = Base64.getEncoder().encodeToString(imgResponse.body().bytes());
                JSONObject imgJSON = new JSONObject();
                imgJSON.put("filename", "visitor_photo.png");
                imgJSON.put("data", "data:image/png;base64,"+encodedString);
                requestJSON.put("img", imgJSON);

                imgResponse.close();

            } catch (IOException ioe) {
                log.error("Error trying to get image from VANTIQ : ", ioe);
            }
        } else {
            return errJson.toMap();
        }

        if (payload.containsKey("uid") && ((String)payload.get("uid") != "")) {
            requestJSON.put("person_idcard", (String)payload.get("uid"));
        } else {
            return errJson.toMap();
        }
        // -- End of build requestPayload ---
        log.info("requestJSON size = " + requestJSON.toString().length());

        // -- Send request to register new visitor ---
        OkHttpClient client = new OkHttpClient().newBuilder().build();
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody body = RequestBody.create(mediaType, requestJSON.toString());

        Request request = new Request.Builder()
            .url(this.sn_url + SenseNebulaConnectorCore.SN_REST_Base)
            .addHeader("Content-Type", "application/json")
            .addHeader("sessionid", this.sn_sessionKey)
            .method("POST", body)
            .build();

        try {
            Response response = client.newCall(request).execute();
            JSONObject retObj = new JSONObject(response.body().string());
            response.close();
            log.info("retObj = " + retObj.toString());

            if (retObj.optInt("code") != 0) {
                log.error("Fail to register visitor in Nebula!");
            } else {
                if (retObj.getInt("code") == 0) 
                {
                    retObj.put("code", 200);
                    retObj.put("msg", "OK");
                }
                
                return retObj.toMap();
            }
            
        } catch (IOException ioe2) {
            log.error("Error registering visitor to Nebula : ", ioe2);
        }
        // -- End of send request ---

        restartKeepNebulaLoginAliveTimer();

        return errJson.toMap();
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte h : hash) {
            String hex = Integer.toHexString(0xff & h);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * CKK: Method to subscribe to SenseNebula events through WebSocket connection
     */
    public void startNebulaWSConnection() {
        if (this.sn_websocketKey != "" && this.sn_url != "") {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .build();

            String ws_url = "";
            if (this.sn_url.startsWith("http://")) {
                ws_url = sn_url.replace("http://", "ws://") + SenseNebulaConnectorCore.SN_WS_Base;
            } else if (this.sn_url.startsWith("https://")) {
                ws_url = sn_url.replace("https://", "wss://") + SenseNebulaConnectorCore.SN_WS_Base;
            }
            log.info("Trying to connect WS to " + ws_url);

            WebSocketCall.create(client, new Request.Builder()
                .url(ws_url)
                .build()).enqueue(new WebSocketListener() {

                    // onOpen event handler. When connected, send subscription request message
                    @Override public void onOpen(WebSocket webSocket, Response response) {
                        nebulaWebSocket = webSocket;

                        log.info("WebSocket Connection to Nebula opened");
                        
                        //send first subscribe message with 
                        //MediaType mediaType = MediaType.parse("application/json");
                        JSONObject subscribeMsg = new JSONObject();
                        subscribeMsg.put("msg_id", SenseNebulaConnectorCore.SN_MSG_SUBS_EVENT);
                        subscribeMsg.put("key", sn_websocketKey);
                        RequestBody requestBody = RequestBody.create(webSocket.TEXT, subscribeMsg.toString());

                        try {
                            nebulaWebSocket.sendMessage(requestBody);
                        } catch (IOException e) {
                            log.error("Error trying to subscribe to events from Nebula : ", e);
                        }
                    }

                    @Override public void onMessage(final ResponseBody message) throws IOException {
                        log.info("Received WS Message! Type = " + message.contentType().toString());
                        JSONObject resultJSON = new JSONObject(message.string());
                        message.close();
                        //log.info(resultJSON.toString());
                        
                        // pass it back to VANTIQ
                        sendBackEvent(resultJSON.toMap());

                    }

                    @Override public void onPong(Buffer payload) {
                        if (payload != null)
                            log.debug("onPong from Nebula : ", payload.toString());
                    }

                    @Override public void onClose(int code, String reason) {
                        log.info("WebSocket connection to Nebula has closed");
                        nebulaWebSocket = null;
                    }

                    @Override public void onFailure(IOException e, Response response) {
                        log.error("WebSocket connection to Nebula Failed : ", e);
                    }
                });
            
        }
    }

    /**
     * CKK: Method used by eventListenServer to send back event messages to VANTIQ via ExtensionWebSocketClient
     * @param event
     */
    public void sendBackEvent(Map event) {
        try {
            if (client.isOpen() &&  client.isAuthed()) { 
                log.info("CKK: sendBackEvent ....");
                
                rawEventLog.debug((new JSONObject(event)).toString() + ",");

                int parts_count = 1;
                String currentTimeStamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
                String source_msg_uid_str = UUID.randomUUID().toString();
                HashMap finalMsgMap = new HashMap();
                JSONObject finalMsg2 = new JSONObject();
                JSONObject finalMsg3 = new JSONObject();
                JSONObject[] finalMsg2Array = null;
                JSONObject[] finalMsg3Array = null;
                boolean finalMsg2_hasMsg = false;
                boolean finalMsg3_hasMsg = false;

                finalMsgMap.put("timeStamp", currentTimeStamp);
                finalMsgMap.put("messageType", "SN_event");
                finalMsgMap.put("source_msg_uid", source_msg_uid_str);

                //remove snap_frame if it exists, because the message size potentially can overflow message buffer
                JSONObject fudgeJSON = new JSONObject(event);
                //log.info("Before Redact/Split size = " + fudgeJSON.toString().length());
                // Check that msg_id = "777"
                // breakup content of "data.img" and "data.snap_buf"
                // link all 3 messages with same uuid in "source_msg_uid", and send
                if (fudgeJSON.optString("msg_id").equals("777") && fudgeJSON.optJSONObject("data") != null) {
                    //log.info("Message of type '777'");
                    JSONObject dataObj = fudgeJSON.optJSONObject("data");
                    //log.info("Got JSON Object of 'data'");
                    //log.info("CKK: source_msg_uid = " + source_msg_uid_str);
                    //log.info("CKK: Recognition Rec for [" + dataObj.optString("camera_name") + "]");

                    if (dataObj.optString("snap_frame") != "") {
                        //log.info("redacting data.snap_frame...");
                        dataObj.put("snap_frame", "----- REDACTED -----");
                        dataObj.put("snap_feat", "----- REDACTED -----");
                    }

                    if (dataObj.optString("img") != "") {
                        //log.info("found data.img .... splitting");
                        /** 
                        finalMsg2.put("timeStamp", currentTimeStamp);
                        finalMsg2.put("messageType", "SN_event");
                        finalMsg2.put("source_msg_uid", source_msg_uid_str);
                        JSONObject msg2Data = new JSONObject();
                        String imgContent = (String)(dataObj.remove("img"));
                        if (imgContent.length() > 250000) {
                            msg2Data.put("img", "----- REDACTED due to size -----");
                        } else {
                            msg2Data.put("img", imgContent);
                        }
                        
                        finalMsg2.put("data", ((new JSONObject()).put("data", msg2Data)) );
                        finalMsg2.put("msg_id", "777");
                        **/
                        JSONObject msg2Data = new JSONObject();
                        String imgContent = (String)(dataObj.remove("img"));
                        finalMsg2Array = splitMessageContent(imgContent, source_msg_uid_str, "img", currentTimeStamp, "SN_event", "777", 250000);
                        parts_count += finalMsg2Array.length;
                        //log.info("Splitted data.img into " + finalMsg2Array.length + " chunks");
                        finalMsg2_hasMsg = true;
                    }

                    if (dataObj.optString("snap_buf") != "") {
                        //log.info("found data.snap_buf .... splitting");
                        /** 
                        finalMsg3.put("timeStamp", currentTimeStamp);
                        finalMsg3.put("messageType", "SN_event");
                        finalMsg3.put("source_msg_uid", source_msg_uid_str);
                        JSONObject msg3Data = new JSONObject();
                        String snapbufContent = (String)(dataObj.remove("snap_buf"));
                        if (snapbufContent.length() > 250000) {
                            msg3Data.put("snap_buf", "----- REDACTED due to size -----");
                        } else {
                            msg3Data.put("snap_buf", snapbufContent);
                        }
                        finalMsg3.put("data", ((new JSONObject()).put("data", msg3Data)) );
                        finalMsg3.put("msg_id", "777");
                        **/
                        JSONObject msg3Data = new JSONObject();
                        String snapbufContent = (String)(dataObj.remove("snap_buf"));
                        finalMsg3Array = splitMessageContent(snapbufContent, source_msg_uid_str, "snap_buf", currentTimeStamp, "SN_event", "777", 250000);
                        parts_count += finalMsg3Array.length;
                        //log.info("Splitted data.snap_buf into " + finalMsg3Array.length + " chunks");
                        finalMsg3_hasMsg = true;
                    }

                    fudgeJSON.put("data", dataObj);    
                    
                } 
                
                //log.info("After Redact/Split size = " + fudgeJSON.toString().length());
                finalMsgMap.put("data", fudgeJSON.toMap());
                finalMsgMap.put("parts_count", parts_count);
                
                //log.info("After Redact/Split = " + fudgeJSON.toString());
                //log.info("Redacted Msg size = " + finalMsgMap.toString().length());
                //log.info("finalMsgMap.source_msg_uid = " + ((String)finalMsgMap.get("source_msg_uid")));
                client.sendNotification(finalMsgMap);

                if (finalMsg2_hasMsg) {
                    for (int i = 0; i<finalMsg2Array.length; i++) {
                        finalMsg2Array[i].put("parts_count", parts_count);
                        //log.info("finalMsg2["+ i +"].source_msg_uid = " + finalMsg2Array[i].getString("source_msg_uid"));
                        //log.info("Split img chunk #" + (i+1) + "/" + finalMsg2Array.length + " size = " + finalMsg2Array[i].toString().length());
                        client.sendNotification(finalMsg2Array[i].toMap());
                    }
                    /** 
                    finalMsg2.put("parts_count", parts_count);
                    log.info("Split #1 size = " + finalMsg2.toString().length());
                    client.sendNotification(finalMsg2.toMap());
                    **/
                }

                if (finalMsg3_hasMsg) {
                    for (int i = 0; i<finalMsg3Array.length; i++) {
                        finalMsg3Array[i].put("parts_count", parts_count);
                        //log.info("finalMsg3["+ i +"].source_msg_uid = " + finalMsg3Array[i].getString("source_msg_uid"));
                        log.info("Split snap_buf chunk #" + (i+1) + "/" + finalMsg3Array.length + " size = " + finalMsg3Array[i].toString().length());
                        client.sendNotification(finalMsg3Array[i].toMap());
                    }
                    /** 
                    finalMsg3.put("parts_count", parts_count);
                    log.info("Split #2 size = " + finalMsg3.toString().length());
                    client.sendNotification(finalMsg3.toMap());
                    **/
                }

                log.info("CKK: message has been splitted into ["+ parts_count + "] part(s)");

                //restartKeepAliveTimer();
            }
            else log.error("Client is NULL?!!!");
        } catch (Exception e) {
            log.error("Error sending message back to VANTIQ", e);
        }
    }

    /**
     * split message content into multiple message templates to deal with oversized messages
     * @param content the actual string to be splitted (e.g. base64 encoded image string)
     * @param parentMsgId uid of parent message to link these message chunks with the original message body
     * @param currentUniqueIdentifier JSON property name of @content
     * @param timeStamp time stamp string
     * @param messageType "SN_event"
     * @param msg_id "777"
     * @param splitSize enter default size of 250000
     * @return
     */
    private JSONObject[] splitMessageContent(String content, String parentMsgId, String currentUniqueIdentifier, String timeStamp, String messageType, String msg_id, int splitSize)
    {
        
        JSONObject msgTemplate;

        List<String> chunks = Lists.newArrayList(Splitter.fixedLength(splitSize).split(content));
        JSONObject[] results = new JSONObject[chunks.size()];
        int chunk_count = 0;
        Iterator<String> chunkIterator = chunks.iterator();
        while(chunkIterator.hasNext()) {
            msgTemplate = new JSONObject();
            msgTemplate.put("timeStamp", timeStamp);
            msgTemplate.put("messageType", messageType);
            msgTemplate.put("source_msg_uid", parentMsgId);
            msgTemplate.put("chuck_num", ++chunk_count);
            msgTemplate.put("chunk_count", chunks.size());
            msgTemplate.put("chunk_id", currentUniqueIdentifier);
            JSONObject insideData = new JSONObject();
            insideData.put(currentUniqueIdentifier, chunkIterator.next());
            insideData.put("msg_id", msg_id);
            msgTemplate.put("data", insideData);

            results[chunk_count - 1] = msgTemplate;
        }
        
        return results;
    }
    /**
     * Method that sets up timer to poll for data from file if that is included in
     * source configuration.
     * 
     * @param files           The list of filenames to read from
     * @param pollingInterval The interval on which we will read data from the files
     *                        (as a String) and send it back as a notification to
     *                        the source
     */
    public void pollFromFiles(List<String> files, int pollingInterval) {
        filenames.addAll(files);
        pollingTimer = new Timer("pollingTimer");
        TimerTask pollingTimerTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    Map responseObject = readFromFiles(filenames);
                    client.sendNotification(responseObject);
                } catch (Exception e) {
                    log.error("", e);
                }
            }
        };
        pollingTimer.schedule(pollingTimerTask, 0, pollingInterval);
    }

    /**
     * Helper method that reads the data from files as a String, and then creates a
     * map of filenames and their data.
     * 
     * @param filenames The list of filenames from which to read data
     * @return A map of filenames to their data (as a String)
     * @throws Exception
     */
    public Map readFromFiles(List<String> filenames) throws Exception {
        Map<String, String> fileData = new LinkedHashMap<>();
        for (String filename : filenames) {
            String data = new String(Files.readAllBytes(Paths.get(filename)));
            fileData.put(filename, data);
        }
        return fileData;
    }

    /**
     * Helper method that reads the data from environment variables as a String, and
     * then creates a map of their names and data.
     * 
     * @param environmentVariables The list of environment variable names
     * @return A map of environment variable names and their data
     */
    public Map readFromEnvironmentVariables(List<String> environmentVariables) {
        Map<String, String> envVarData = new LinkedHashMap<>();
        for (String envVarName : environmentVariables) {
            String data = System.getenv(envVarName);
            envVarData.put(envVarName, data);
        }
        return envVarData;
    }

    /**
     * Method called by the publishHandler. Processes the request and sends response
     * if needed.
     * 
     * @param message The publish message
     */
    public void executePublish(ExtensionServiceMessage message) {
        Map<String, ?> request = (Map<String, ?>) message.getObject();
        Map<String, Map> responseMap = processRequest(request, null);
        if (responseMap != null) {
            client.sendNotification(responseMap);
        }
    }

    /**
     * Method called by the queryHandler. Processes the request and sends query
     * response or query error.
     * 
     * @param message The query message
     */
    public void executeQuery(ExtensionServiceMessage message) {
        Map<String, ?> request = (Map<String, ?>) message.getObject();
        String replyAddress = ExtensionServiceMessage.extractReplyAddress(message);
        Map<String, Map> responseMap = processRequest(request, replyAddress);
        if (responseMap != null) {
            client.sendQueryResponse(200, replyAddress, responseMap);
        }
    }

    /**
     * Helper method called by executePublish and executeQuery that parses the
     * request and fetches the appropriate data
     * 
     * @param request      The publish or query request
     * @param replyAddress The replyAddress if this is a query. If the value is
     *                     non-null, then we can send query errors
     * @return The map of filenames and/or environment variables, depending on what
     *         was included in the request.
     */
    public Map<String, Map> processRequest(Map<String, ?> request, String replyAddress) {
        Map<String, Map> responseMap = new LinkedHashMap<>();
        Map<String, ?> resultsMap = null;
        /**
         * CKK: This part should implement logic to on-board a visitor record to
         * SenseLink 1. Requires image 2. Requires visitor information
         */
        log.info(request.toString());
        log.info("ReplyAddress = " + replyAddress);
        if (((String) request.get("command")).equals("addVisitor")) {
            resultsMap = addVisitorToSenseNebula(request);
        } 
        
        responseMap.put("Results", resultsMap);
        /**
        // First we check to make sure that both parameters were included, and return an
        // error if not
        if (!(request.get(ENVIRONMENT_VARIABLES) instanceof List) && !(request.get(FILENAMES) instanceof List)) {
            log.error("The request cannot be processed because it does not contain a valid list of filenames or "
                    + "environmentVariables. At least one of these two parameters must be provided.");
            if (replyAddress != null) {
                client.sendQueryError(replyAddress, Exception.class.getCanonicalName(),
                        "The request cannot be processed because it does not contain a valid list of "
                                + "filenames or environmentVariables. At least one of these two parameters must be "
                                + "provided.",
                        null);
            }
            return null;
        }

        // Next we check for environment variables in the request and grab their data
        if (request.get(ENVIRONMENT_VARIABLES) instanceof List) {
            List environmentVariables = (List) request.get(ENVIRONMENT_VARIABLES);
            if (SenseNebulaConnectorHandleConfiguration.checkListValues(environmentVariables)) {
                responseMap.put("environmentVariables", readFromEnvironmentVariables(environmentVariables));
            } else {
                log.error("The request was unable to be processed because the 'environmentVariables' list contained "
                        + "either non-String values, or empty Strings. Request: {}", request);
                if (replyAddress != null) {
                    client.sendQueryError(replyAddress, Exception.class.getCanonicalName(),
                            "The request was unable to be processed because the 'environmentVariables'"
                                    + " list contained either non-String values, or empty Strings.",
                            null);
                }
                return null;
            }
        }

        // Finally we get the data from the files if they were specified
        if (request.get(FILENAMES) instanceof List) {
            List filenames = (List) request.get(FILENAMES);
            if (SenseNebulaConnectorHandleConfiguration.checkListValues(filenames)) {
                try {
                    Map fileData = readFromFiles(filenames);
                    responseMap.put("files", fileData);
                } catch (Exception e) {
                    log.error("An exception occurred while processing the filenames provided in the request", e);
                    if (replyAddress != null) {
                        client.sendQueryError(replyAddress, Exception.class.getCanonicalName(),
                                "An exception occurred while processing the filenames provided in the"
                                        + " request. Exception: " + e.getClass() + ":" + e.getMessage(),
                                null);
                    }
                    return null;
                }
            } else {
                log.error("The request was unable to be processed because the 'filenames' list contained either "
                        + "non-String values, or empty Strings. Request: {}", request);
                if (replyAddress != null) {
                    client.sendQueryError(replyAddress, Exception.class.getCanonicalName(),
                            "The request was unable to be processed because the 'filenames' list "
                                    + "contained either non-String values, or empty Strings.",
                            null);
                }
                return null;
            }
        }
        **/

        if (responseMap.isEmpty()) {
            return null;
        } else {
            return responseMap;
        }
    }

    /**
     * Waits for the connection to succeed or fail, logs and exits if the connection
     * does not succeed within {@code timeout} seconds.
     *
     * @param timeout The maximum number of seconds to wait before assuming failure
     *                and stopping
     * @return true if the connection succeeded, false if it failed to connect
     *         within {@code timeout} seconds.
     */
    public boolean exitIfConnectionFails(int timeout) {
        boolean sourcesSucceeded = false;
        try {
            sourcesSucceeded = client.getSourceConnectionFuture().get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Timeout: full connection did not succeed within {} seconds: {}", timeout, e);
        } catch (Exception e) {
            log.error("Exception occurred while waiting for webSocket connection", e);
        }
        if (!sourcesSucceeded) {
            log.error("Failed to connect to all sources.");
            if (!client.isOpen()) {
                log.error("Failed to connect to server url '" + targetVantiqServer + "'.");
            } else if (!client.isAuthed()) {
                log.error("Failed to authenticate within " + timeout + " seconds using the given authentication data.");
            } else {
                log.error("Failed to connect within 10 seconds");
            }
            return false;
        }
        return true;
    }

    /**
     * Returns the name of the source that it is connected to.
     * 
     * @return The name of the source that it is connected to.
     */
    public String getSourceName() {
        return sourceName;
    }

    /**
     * Closes all resources held by this program except for the
     * {@link ExtensionWebSocketClient}.
     */
    public void close() {
        if (pollingTimer != null) {
            pollingTimer.cancel();
            pollingTimer = null;
        }

        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }

        if (keepNebulaAliveTimer != null) {
            keepNebulaAliveTimer.cancel();
            keepNebulaAliveTimer = null;
        }
    }

    /**
     * Closes all resources held by this program and then closes the connection.
     */
    public void stop() {
        close();
        if (client != null && client.isOpen()) {
            client.stop();
            client = null;
        }
    }
}
