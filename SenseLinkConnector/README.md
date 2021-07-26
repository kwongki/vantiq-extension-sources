# Overview

**_Note: This forked branch was updated with older version codes from extjsdk before the upgrade to okhttp3 version (3.4.1)._**

**_Use only for PrimusTech COC project. Code to be migrated to latest version of extjsdk_** 


This document outlines the SenseLink Connector. 
The Connector takes care of communication between VANTIQ and SenseLink AIoT Platform. It is implemented to perform the following functions:

* Starts a local web server and listen to events on HTTP POST. Sends events back to Vantiq
* _SenseLink will be configured to push event messages to this HTTP POST endpoint_
* Register a new facial recognition record in SenseLink Platform via a Query command. New record will be created in "Visitors" pre-defined database 




# Prerequisites <a name="pre" id="pre"></a>

An understanding of the VANTIQ Extension Source SDK is assumed. Please read the [Extension Source README.md](../README.md) 
for more information.

The user should use the source definition file to install the Extension Source in namespace to be used. See ``sourceimpl.json``.

# Setting Up Your Machine <a name="machine" id="machine"></a>

## Repository Contents

*   **SenseLinkConnectorMain** -- The main function for the program. Connects to sources as specified in the configuration 
file.
*   **SenseLinkConnectorCore** -- Does the work of authentication with SenseLink Platform, starts to webserver POST endpoint, listening to event messages, and sends it back to Vantiq.
*   **SenseLinkConnectorHandleConfiguration** -- Processes the source configuration
*   **SenseLinkEventListenerServer** -- Simple HTTP server listening to POST endpoint for event messages from SenseLink Platform

## How to Run the Program

1.  Clone this repository (vantiq-extension-sources) and navigate into `<repo location>/vantiq-extension-sources`.
2.  Run `./gradlew SenseLinkConnector:assemble`.
3.  Navigate to `<repo location>/vantiq-extension-sources/SenseLinkConnector/build/distributions`. The zip and tar files both 
contain the same files, so choose whichever you prefer.
4.  Uncompress the file in the location that you would like to install the program.
6.  Run `<install location>/bin/SenseLinkConnector` with a local server.config file or specifying the 
[server config file](#serverConfig) as the first argument. Note that the `server.config` file can be placed in `<install location>/server.config`.

To run the program directory from the repository directory:
1. Run this `./gradlew SenseLinkConnector:run --args='./server.config'`


## Logging
To change the logging settings, edit the logging config file `<install location>/SenseLinkConnector/src/main/resources/log4j2.xml`,
which is an [Apache Log4j configuration file.](https://logging.apache.org/log4j/2.x/manual/configuration.html). The logger 
name for each class is the class's fully qualified class name, *e.g.* "io.vantiq.extjsdk.ExtensionWebSocketClient".  

The project is configured with one daily rolling log file:
* `./logs/senselink.log`

This log file is meant for capturing debugging details, can be turned off in production mode.

## Server Config File
(Please read the [SDK's server config documentation](../extjsdk/README.md#serverConfig) first.)

### Vantiq Options
*   **authToken**: Required. The authentication token to connect with. These can be obtained from the namespace admin.
*   **sources**: Required. A comma separated list of the sources to which you wish to connect. Any whitespace will be
    removed when read.
*   **targetServer**: Required. The Vantiq server hosting the sources.

# Setting Up Your Vantiq Source <a name="vantiq" id="vantiq"></a>

## Source Configuration

To set up the Source in the Vantiq system, you will need to add a Source to your project. Please check the [Prerequisites](#pre) 
to make sure you have properly added a Source Implementation definition to your Vantiq namespace. Once this is complete, 
you can select SenseLinkConnector (or whatever you named your Source Implementation) as the Source Type. You will then need 
to fill out the Source Configuration Document.

The Configuration document may look similar to the following example:

    {
        "senseLinkConfig": {
            "general": {
                "eventListenPort": 8088,
                "eventListenServer": "127.0.0.1",
                "eventListenServerPath": "/events",
                "senseLink_URL": "http://127.0.0.1",
                "senseLink_appKey": "xxxxxxxxx",
                "senseLink_appSecret": "xxxxxxxxxxx"
            }
        }
    }

### Options Available for senseLinkConfig
**Note:** The "senseLinkConfig" and "general" portions must be included in the source configuration, but they can be left
empty.
*   **eventListenerPort**: Required. Port for local HTTP server
*   **eventListenerServer**: Required. Hostname for local HTTP server
*   **eventListenerServerPath**: Optional. The local HTTP server will take the format `http://<eventListenerServer>:<eventListenerPort>/<eventListenerServerPath>`
*   **senseLink_URL**: Required. URL to SenseLink Platform
*   **senseLink_appKey**: Required. 
*   **senseLink_appSecret**: Required.



## Select Statements

The current implementation allows Select statements to be used for adding a new facial recognition record onto SenseNebula Edge Box. 

The following shows the example using a Vail Select Statement:
```
    Var result = SELECT * FROM SOURCE SenseLinkSource WITH 
        command : "addVisitor",
        name : _name,
        uid : _uid,
        imageURL : _photo_url
```
parameters:
- command: "addVisitor"
- name: Name of the person to be added into database
- uid: unique user ID
- imageURL: publicly accessible URL of profile image stored on Vantiq Document Store.

### Query Response Format
_to be updated_


## Licensing
The source code uses the [MIT License](https://opensource.org/licenses/MIT).  

okhttp3, log4j, and jackson-databind are licensed under
[Apache Version 2.0 License](http://www.apache.org/licenses/LICENSE-2.0).  

slf4j is licensed under the [MIT License](https://opensource.org/licenses/MIT).  
