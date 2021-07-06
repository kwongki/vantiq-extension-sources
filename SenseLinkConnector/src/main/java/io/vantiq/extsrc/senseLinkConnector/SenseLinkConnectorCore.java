/*
 * Copyright (c) 2021 Vantiq, Inc.
 *
 * All rights reserved.
 *
 * SPDX: MIT
 */
package io.vantiq.extsrc.senseLinkConnector;

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

import okhttp3.*;
import org.json.*;
import org.apache.commons.text.StringEscapeUtils;

public class SenseLinkConnectorCore {

    String sourceName;
    String authToken;
    String targetVantiqServer;

    private String sl_appKey = "";
    private String sl_appSecret = "";
    private String sl_url = "";
    private String sl_dev_product = "";
    private String sl_dev_provider = "";
    private String sl_dev_appEdition = "";
    private String sl_dev_coreEdition = "";
    private String sl_dev_edition = "";
    private String sl_dev_receptionBot_Id = "";
    private int sl_visitor_default_groupId = -1;

    private Thread keepAliveThread;

    SenseLinkConnectorHandleConfiguration testConnectorHandleConfiguration;
    ExtensionWebSocketClient client = null;
    SenseLinkEventListenerServer eventListenServer = null;

    List<String> filenames = new ArrayList<>();
    HashMap<String, String> ldid_deviceMap = new HashMap();

    final Logger log;
    final static int RECONNECT_INTERVAL = 5000;

    static final String ENVIRONMENT_VARIABLES = "environmentVariables";
    static final String FILENAMES = "filenames";
    static final String SENSELINK_APPKEY = "senseLink_appKey";
    static final String SENSELINK_APPSECRET = "senseLink_appSecret";
    static final String SENSELINK_BASEURL = "senseLink_URL";
    static final String LOCAL_EVENT_LISTENER_URL = "eventListenServer";
    static final String LOCAL_EVENT_LISTENER_PORT = "eventListenPort"; 
    static final String LOCAL_EVENT_LISTENER_PATH = "eventListenServerPath";
    static final String SL_REST_Base = "/sl/api/v5/";
    static final String SL_RESTEndPoint_Version = "serverversion";
    static final String SL_RESTEndPoint_Users = "users";
    static final String SL_RESTEndPoint_Types = "types";
    static final String SL_RESTEndPoint_Images = "images";
    static final String SL_RESTEndPoint_Groups = "groups";
    static final String SL_RESTEndPoint_Devices = "devices";

    // Timer used if source is configured to poll from files
    Timer pollingTimer;
    Timer keepAliveTimer;

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
     * Creates a new SenseLinkConnectorCore with the settings given.
     * 
     * @param sourceName         The name of the source to connect to.
     * @param authToken          The authentication token to use to connect.
     * @param targetVantiqServer The url to connect to.
     */
    public SenseLinkConnectorCore(String sourceName, String authToken, String targetVantiqServer) {
        log = LoggerFactory.getLogger(this.getClass().getCanonicalName() + '#' + sourceName);
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
        testConnectorHandleConfiguration = new SenseLinkConnectorHandleConfiguration(this);

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
        //restartKeepAliveTimer();
    }

    /**
     * CKK: Function to restart scheduled task for ping keep alive.
     */
    public void restartKeepAliveTimer() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }

        keepAliveTimer = new Timer("pingTimer");
        TimerTask aliveTask = new TimerTask() {
            @Override
            public void run() {
                LocalDateTime currentTime = LocalDateTime.now();
                log.info("Ping KeepAlive Message!!!");
                String jsonObjectString = "{\"timeStamp\" : \""+ currentTime.format(DateTimeFormatter.ISO_DATE_TIME) +"\", \"messageType\": \"ping\", \"data\" : {";
                jsonObjectString += "\"product\" : \"" + sl_dev_product + "\",";
                jsonObjectString += "\"provider\" : \"" + sl_dev_provider + "\",";
                jsonObjectString += "\"appEdition\" : \"" + sl_dev_appEdition + "\",";
                jsonObjectString += "\"coreEdition\" : \"" + sl_dev_coreEdition + "\",";
                jsonObjectString += "\"edition\" : \"" + sl_dev_edition + "\"";
                jsonObjectString += "}}";
                JSONObject pingJson = new JSONObject(jsonObjectString);
                client.sendNotification(pingJson.toMap());
                //client.sendPing();
            }
        };
        keepAliveTimer.schedule(aliveTask, 100, 280000);
    }    

    /**
     * CKK: 17-05-2021
     * Method to check SenseLink platform version. Use to confirm platform is still alive. 
     * It will also set the url, appKey and appSecret for this class
     * Initiate HTTP POST query to SenseLink to confirm connectivity (https://{{serverIpPort}}/sl/api/v5/serverversion)
     * returns similar to :
     * {
     *      "code": 200,
     *       "message": "OK",
     *       "data": {
     *           "date": "2021-04-09",
     *           "product": "SenseLink Standard",
     *           "provider": "ST-BI",
     *           "appEdition": "1.2.2.109",
     *           "coreEdition": "1.0.17.109",
     *           "edition": "2.3.3.0"
     *       }
     *   }
     * @param url
     * @param appKey
     * @param appSecret
     * @return
     */
    public Map checkSenseLinkVersion(String url, String appKey, String appSecret) {
        String currentTimeStamp = Long.toString(System.currentTimeMillis());
        final MessageDigest digest;
        final byte[] encodedhash;
        String currentSign = "";

        try {
            digest = MessageDigest.getInstance("MD5");
            encodedhash = digest.digest((currentTimeStamp+"#"+appSecret).getBytes(StandardCharsets.UTF_8));
            currentSign = bytesToHex(encodedhash);     
        } catch (NoSuchAlgorithmException ne) {
            ne.printStackTrace();
        }
        
        //log.info("appKey = " + appKey);
        //log.info("appSecret = " + appSecret);
        //log.info("timestamp = " + currentTimeStamp);
        //log.info("sign = " + currentSign);

        OkHttpClient client = new OkHttpClient().newBuilder().build();
    
        Request request = new Request.Builder()
            .url(url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Version)
            .addHeader("appKey", appKey)
            .addHeader("sign", currentSign)
            .addHeader("timestamp", currentTimeStamp)
            .build();

        try {
            Response response = client.newCall(request).execute();
            //log.info("Response = " + response.body().string());
            JSONObject jsonObj = new JSONObject(response.body().string());
            //log.info("JSON Object = " + jsonObj.toString());
            response.close();

            // Last step: save url, appkey and appsecret, since it's working
            this.sl_appKey = appKey;
            this.sl_appSecret = appSecret;
            this.sl_url = url;

            JSONObject dataObj = jsonObj.getJSONObject("data");
            this.sl_dev_appEdition = dataObj.getString("appEdition");
            this.sl_dev_coreEdition = dataObj.getString("coreEdition");
            this.sl_dev_edition = dataObj.getString("edition");
            this.sl_dev_product = dataObj.getString("product");
            this.sl_dev_provider = dataObj.getString("provider");


            // Grab the id for "Reception_Bot" from Type = 1
            Request requestGetId = new Request.Builder()
                .url(url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Users + "?type=1&name=Reception_Bot")
                .addHeader("appKey", appKey)
                .addHeader("sign", currentSign)
                .addHeader("timestamp", currentTimeStamp)
                .build();
                
            try {
                Response response2 = client.newCall(requestGetId).execute();
                JSONObject jsonObj2 = new JSONObject(response2.body().string());
                //log.info("JSON Object = " + jsonObj2.toString());
                response2.close();

                if (jsonObj2.getInt("code") == 200 && jsonObj2.getJSONObject("data").getInt("total") == 1) {
                    JSONObject record = jsonObj2.getJSONObject("data").getJSONArray("resultList").getJSONObject(0);
                    //log.info(record.toString());
                    this.sl_dev_receptionBot_Id = Integer.toString(record.getInt("id"));
                }
            } catch (IOException e1) {
                log.error("Error getting Reception_Bot ID: ", e1);
            }
            /*** End of Grab id for "Reception_Bot" */

            // Grab the default group id for Type = 2
            Request requestGetDefaultGroupId = new Request.Builder()
                .url(url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Groups + "?type=2")
                .addHeader("appKey", appKey)
                .addHeader("sign", currentSign)
                .addHeader("timestamp", currentTimeStamp)
                .build();

            try {
                Response response3 = client.newCall(requestGetDefaultGroupId).execute();
                JSONObject jsonObj3 = new JSONObject(response3.body().string());
                response3.close();
                
                //log.info("Results of GetGroups = " + jsonObj3.toString());

                if (jsonObj3.getInt("code") == 200 && jsonObj3.getJSONObject("data").getJSONArray("resultList").length() > 0) {
                    JSONArray groupListArray = jsonObj3.getJSONObject("data").getJSONArray("resultList");
                    for (int i=0; i<groupListArray.length(); i++) {
                            JSONObject currObj = groupListArray.getJSONObject(i);
                            if (currObj.getInt("isDefault") == 1) {
                                sl_visitor_default_groupId = currObj.getInt("groupId");
                                log.info("Got Default Visitor Group Id = " + sl_visitor_default_groupId);
                                break;
                            }
                    }
                }

            } catch (IOException e1) {
                log.error("Error getting default SenseLink visitor group: ", e1);
            }
            /*** End of Grab default group id for Type =2  */

            // Grab list of device information
            Request requestGetDeviceList = new Request.Builder()
                .url(url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Devices)
                .addHeader("appKey", appKey)
                .addHeader("sign", currentSign)
                .addHeader("timestamp", currentTimeStamp)
                .build();

            try {
                Response response4  = client.newCall(requestGetDeviceList).execute();
                JSONObject jsonObj4 = new JSONObject(response4.body().string());
                response4.close();

                if (jsonObj4.getInt("code") == 200 && jsonObj4.getJSONObject("data").optJSONArray("resultList") != null) {
                    JSONArray deviceList = jsonObj4.getJSONObject("data").getJSONArray("resultList");
                    for (int i=0; i<deviceList.length(); i++) {
                        JSONObject curObj = deviceList.getJSONObject(i);
                        ldid_deviceMap.put(curObj.getString("ldid"), curObj.getString("name"));
                    }
                }
            } catch (IOException e2) {
                log.error("Error getting device info from SenseLink: ", e2);
            }
            /*** End of Grab device info list */

            return jsonObj.toMap();
            //return response.body().string();
        } catch (IOException e) {
            log.error("Error getting SenseLink Version: ", e);
        }

        return null;
    }

    /**
     * CKK: 21-05-2021
     * Method to add visitor record to SenseLink platform.
     * TO BE TESTED
     * @param payload
     * @return
     */
    public Map addVisitorToSenseLink(Map payload) {
        String currentTimeStamp = Long.toString(System.currentTimeMillis());
        final MessageDigest digest;
        final byte[] encodedhash;
        String currentSign = "";

        String errorResponsePayload = "{ \"error\": \"Error adding Visitor to SenseLink\" }";
        JSONObject errJson = new JSONObject(errorResponsePayload);
        log.info("Inside addVisitorToSenseLink .... ");

        try {
            digest = MessageDigest.getInstance("MD5");
            encodedhash = digest.digest((currentTimeStamp+"#"+this.sl_appSecret).getBytes(StandardCharsets.UTF_8));
            currentSign = bytesToHex(encodedhash);     
        } catch (NoSuchAlgorithmException ne) {
            ne.printStackTrace();
        }

        JSONObject payloadJSON = new JSONObject();

        // Start building up the POST body payload
        payloadJSON.put("type", 2);

        if (payload.containsKey("name") && ((String)payload.get("name") != "")) {
            payloadJSON.put("name", (String)payload.get("name"));
        }
        else {
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
                payloadJSON.put("avatarBytes", encodedString);
                imgResponse.close();

            } catch (IOException ioe) {
                log.error("Error trying to get image from VANTIQ", ioe);
            }
        }
        
        //payloadJSON.put("avatarShowBytes",  "xxxx");
        //payloadJSON.put("icNumber", "xxxx");
        if (payload.containsKey("uid") && ((String)payload.get("uid") != "")) {
            //log.info((String)payload.get("uid"));
            payloadJSON.put("idNumber", (String)payload.get("uid"));
        }


        LocalDateTime startTime = LocalDateTime.now();
        LocalDateTime endTime = startTime.plusHours(8);
        DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

        payloadJSON.put("receptionUserId", this.sl_dev_receptionBot_Id);
        payloadJSON.put("dateTimeFrom", startTime.format(dtFormatter));
        payloadJSON.put("dateTimeTo", endTime.format(dtFormatter));
        /** 
        payloadJSON.put("mobile", "xxxx");
        payloadJSON.put("areaCode", "xxxx");
        payloadJSON.put("mail", "xxxx");
        payloadJSON.put("position", "xxxx");
        payloadJSON.put("gender", "xxxx");
        payloadJSON.put("prompt", "xxxx");
        payloadJSON.put("remark", "xxxx");
        payloadJSON.put("birthday", "xxxx");
        payloadJSON.put("pin", "xxxx");
        payloadJSON.put("forbiddenStart", "xxxx");
        payloadJSON.put("forbiddenEnd", "xxxx");
        payloadJSON.put("guestCompany", "xxxx");
        payloadJSON.put("guestPurpose", "xxxx");
        payloadJSON.put("guestLevel", "xxxx");
        **/
        //String testJsonString = "{ \"type\":2, \"name\":\"CKK2\", \"receptionUserId\":\"1\", \"dateTimeFrom\":\""+startTime.format(dtFormatter)+"\",\"dateTimeTo\":\""+endTime.format(dtFormatter)+"\"}";
        //log.info(payloadJSON.toString());

        // End of building POST body payload

        OkHttpClient client = new OkHttpClient().newBuilder().build();
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody body = RequestBody.create(mediaType, payloadJSON.toString());
    
        Request request = new Request.Builder()
            .url(this.sl_url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Users)
            .addHeader("appKey", this.sl_appKey)
            .addHeader("sign", currentSign)
            .addHeader("timestamp", currentTimeStamp)
            .addHeader("Content-Type", "application/json")
            .method("POST", body)
            .build();

        try {
            Response response = client.newCall(request).execute();
            // log.info("Response = " + response.body().string());
            JSONObject jsonObj = new JSONObject(response.body().string());
            log.info("JSON Object = " + jsonObj.toString());
            response.close();

            //log.info((jsonObj.getJSONObject("data")).optInt("id"));
            if ((jsonObj.getInt("code") == 200) && (jsonObj.getJSONObject("data").optInt("id") > 0)) {
                int created_user_id = jsonObj.getJSONObject("data").getInt("id");
                String this_url = this.sl_url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Users + "/" + Integer.toString(created_user_id) +"/" + SenseLinkConnectorCore.SL_RESTEndPoint_Groups;
                //log.info("HTTP PUT to : " + this_url);

                JSONObject putPayLoad = new JSONObject();
                putPayLoad.put("groupIds", (new int[] {sl_visitor_default_groupId}));
                RequestBody putBody = RequestBody.create(mediaType, putPayLoad.toString());
                //log.info(putPayLoad.toString());

                Request requestUpdateGrouping = new Request.Builder()
                    .url(this_url)
                    .addHeader("appKey", this.sl_appKey)
                    .addHeader("sign", currentSign)
                    .addHeader("timestamp", currentTimeStamp)
                    .addHeader("Content-Type", "application/json")
                    .method("PUT", putBody)
                    .build();

                try {
                    Response response2 = client.newCall(requestUpdateGrouping).execute();
                    JSONObject resultJSON = new JSONObject(response2.body().string());
                    response2.close();

                    log.info("Update Group Info for user (" + payloadJSON.getString("name") +") " + resultJSON.getString("message"));

                } catch (IOException e1) {
                    log.error("Error updating group info for user (" + payloadJSON.getString("name") +") : ", e1);
                }

            }
            return jsonObj.toMap();
            //return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return errJson.toMap();

    }

    /**
     * Helper function to get Image from SenseLink
     * @param imgType
     * @param imgId
     * @return
     */
    public Map getImagefromSenseLink(int imgType, String imgId) {
        String currentTimeStamp = Long.toString(System.currentTimeMillis());
        final MessageDigest digest;
        final byte[] encodedhash;
        String currentSign = "";

        try {
            digest = MessageDigest.getInstance("MD5");
            encodedhash = digest.digest((currentTimeStamp+"#"+this.sl_appSecret).getBytes(StandardCharsets.UTF_8));
            currentSign = bytesToHex(encodedhash);     
        } catch (NoSuchAlgorithmException ne) {
            ne.printStackTrace();
        }

        OkHttpClient client = new OkHttpClient().newBuilder().build();
    
        Request request = new Request.Builder()
            .url(this.sl_url + SenseLinkConnectorCore.SL_REST_Base + SenseLinkConnectorCore.SL_RESTEndPoint_Types 
                + "/" + Integer.toString(imgType) + "/" + SenseLinkConnectorCore.SL_RESTEndPoint_Images + "/"
                + imgId )
            .addHeader("appKey", this.sl_appKey)
            .addHeader("sign", currentSign)
            .addHeader("timestamp", currentTimeStamp)
            .build();

        try {
            Response response = client.newCall(request).execute();
            //log.info("Response = " + response.body().string());
            JSONObject jsonObj = new JSONObject(response.body().string());
            //log.info("JSON Object = " + jsonObj.toString());
            response.close();

            return jsonObj.toMap();
        } catch (IOException ioe) {
            log.error("Error getting image from SenseLink : ", ioe);
        }

        return null;
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
     * CKK: Method to start HTTP Server for listening to events from SenseLink
     * @param serverUrl
     * @param serverPort
     * @param serverPath
     */
    public void startListenerServer(String serverUrl, int serverPort, String serverPath) {
        // This will handle reconnect situations, where eventListenServer already exists. Stop it and restart
        if (eventListenServer != null) 
        {
            eventListenServer.stop();
        }

        eventListenServer = new SenseLinkEventListenerServer(this, serverUrl, serverPort, serverPath);
    }

    /**
     * CKK: Method used by eventListenServer to send back event messages to VANTIQ via ExtensionWebSocketClient
     * @param event
     */
    public void sendBackEvent(Map event) {
        try {
            if (client.isOpen() &&  client.isAuthed()) { 
                //log.info("CKK: client is open is authed....");
                HashMap finalMsgMap = new HashMap();
                finalMsgMap.put("timeStamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
                finalMsgMap.put("messageType", "SL_event");
                log.info("event content BEFORE = " + event.toString());
                log.info("deviceMap = " + ldid_deviceMap.toString());

                // add in device name based on ldid
                HashMap event_data = (HashMap) event.get("data");

                if (event_data.containsKey("ldid")) {
                    String device_name = (String) ldid_deviceMap.get( (String)event_data.get("ldid") );
                    log.info("Device Name from deviceMap = " + device_name);
                    if (device_name != null) {
                        event_data.put("device_name", device_name);
                        event.put("data", event_data);
                    }
                }
                finalMsgMap.put("data", event);
                log.info("event content AFTER = " + event.toString());
                client.sendNotification(finalMsgMap);

                //restartKeepAliveTimer();
            }
            else log.error("Client is NULL?!!!");
        } catch (Exception e) {
            log.error("Error sending message back to VANTIQ", e);
        }
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
            resultsMap = addVisitorToSenseLink(request);
        } else if (((String) request.get("command")).equals("getImage")) {
            int imageType = (Integer) request.get("imageType");
            String imageId = (String) request.get("imageId");
            resultsMap = getImagefromSenseLink(imageType, imageId);
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
            if (SenseLinkConnectorHandleConfiguration.checkListValues(environmentVariables)) {
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
            if (SenseLinkConnectorHandleConfiguration.checkListValues(filenames)) {
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
