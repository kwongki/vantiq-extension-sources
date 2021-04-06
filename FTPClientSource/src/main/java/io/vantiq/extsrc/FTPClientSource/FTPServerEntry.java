package io.vantiq.extsrc.FTPClientSource;

import java.util.Map;

import io.vantiq.extsrc.FTPClientSource.exception.VantiqFTPClientException;

;

public class FTPServerEntry {
    public String name;
    public String server;
    public Integer port;
    public String username;
    public String password;
    public boolean enable;
    public String remoteFolderPath;
    public String localFolderPath; 
    public Integer ageInDays;
    public Integer connectTimeout;

    public FTPServerEntry(Map<String, Object> o, FTPServerEntry def) throws VantiqFTPClientException {
        
        if (o.get(FTPClientHandleConfiguration.SERVER_NAME) instanceof String) {
            name = (String) o.get(FTPClientHandleConfiguration.SERVER_NAME);
        } else {
            throw new VantiqFTPClientException("Attribute name must exists in configuration");
        }

        if (o.get(FTPClientHandleConfiguration.SERVER_IP) != null) {
            server = (String) o.get(FTPClientHandleConfiguration.SERVER_IP);
        } else {
            server = def.server;
        }

        if (o.get(FTPClientHandleConfiguration.SERVER_ENABLE) instanceof String) {
            enable = Boolean.parseBoolean((String) o.get(FTPClientHandleConfiguration.SERVER_ENABLE));
        } else if (o.get(FTPClientHandleConfiguration.SERVER_ENABLE) instanceof Boolean) { 
            enable = (Boolean) o.get(FTPClientHandleConfiguration.SERVER_ENABLE);
        }


        if (o.get(FTPClientHandleConfiguration.SERVER_PORT) instanceof Integer) {
            port = Integer.parseInt((String) o.get(FTPClientHandleConfiguration.SERVER_PORT));
        } else {
            port = def.port;
        }

        if (o.get(FTPClientHandleConfiguration.CONNECT_TIMEOUT) instanceof Integer) {
            connectTimeout = Integer.parseInt((String) o.get(FTPClientHandleConfiguration.CONNECT_TIMEOUT));
        } else {
            connectTimeout = def.connectTimeout;
        }

        if (o.get(FTPClientHandleConfiguration.AGE_IN_DAYS_KEYWORD) instanceof Integer) {
            ageInDays = Integer.parseInt((String) o.get(FTPClientHandleConfiguration.AGE_IN_DAYS_KEYWORD));
        } else {
            ageInDays = def.ageInDays;
        }

        if (o.get(FTPClientHandleConfiguration.USERNAME) instanceof String) {
            username = (String) o.get(FTPClientHandleConfiguration.USERNAME);
        } else {
            username = def.username;
        }
        if (o.get(FTPClientHandleConfiguration.PASSWORD) instanceof String) {
            password = (String) o.get(FTPClientHandleConfiguration.PASSWORD);
        } else {
            password = def.password;
        }

        if (o.get(FTPClientHandleConfiguration.REMOTE_FOLDER_PATH) instanceof String) {
            remoteFolderPath = (String) o.get(FTPClientHandleConfiguration.REMOTE_FOLDER_PATH);
        } else {
            remoteFolderPath = def.remoteFolderPath;
        }
        if (o.get(FTPClientHandleConfiguration.LOCAL_FOLDER_PATH) instanceof String) {
            localFolderPath = (String) o.get(FTPClientHandleConfiguration.LOCAL_FOLDER_PATH);
        } else {
            localFolderPath = def.localFolderPath;
        }
    }

    public FTPServerEntry() {

    }

}