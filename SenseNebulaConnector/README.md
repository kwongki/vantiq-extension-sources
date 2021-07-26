# Overview

**_Note: This forked branch was updated with older version codes from extjsdk before the upgrade to okhttp3 version (3.4.1)._**

**_Use only for PrimusTech COC project. Code to be migrated to latest version of extjsdk_** 


This document outlines the SenseNebula Connector. 
The Connector takes care of communication between VANTIQ and SenseNebula AIE Edge Box. It is implemented to perform the following functions:

* Authenticate with username/password to SenseNebula Edge Box
* Subscribe to WebSocket endpoint from SenseNebula Edge Box and listen for event messages to send backto Vantiq
* Register a new facial recognition record in SenseNebula Edge Box via a Query command. New record will be created in database defined in **FR_dbName** attribute in [Source Config](#sourceConfig)


# Prerequisites <a name="pre" id="pre"></a>

An understanding of the VANTIQ Extension Source SDK is assumed. Please read the [Extension Source README.md](../README.md) 
for more information.

The user should use the source definition file to install the Extension Source in namespace to be used. See ``sourceimpl.json``.


# Setting Up Your Machine <a name="machine" id="machine"></a>

## Repository Contents

*   **SenseNebulaConnectorMain** -- The main function for the program. Connects to sources as specified in the configuration 
file.
*   **SenseNebulaConnectorCore** -- Does the work of authentication with SenseNebula Edge Box, Subscribe to Websocket endpoint, listening to event messages, and sends it back to Vantiq.
*   **SenseNebulaConnectorHandleConfiguration** -- Processes the source configuration

## How to Run the Program

1.  Clone this repository (vantiq-extension-sources) and navigate into `<repo location>/vantiq-extension-sources`.
2.  Run `./gradlew SenseNebulaConnector:assemble`.
3.  Navigate to `<repo location>/vantiq-extension-sources/SenseNebulaConnector/build/distributions`. The zip and tar files both 
contain the same files, so choose whichever you prefer.
4.  Uncompress the file in the location that you would like to install the program.
6.  Run `<install location>/bin/SenseNebulaConnector` with a local server.config file or specifying the 
[server config file](#serverConfig) as the first argument. Note that the `server.config` file can be placed in `<install location>/server.config`.

To run the program directory from the repository directory:
1. Run this `./gradlew SenseNebulaConnector:run --args='./server.config'`



## Logging
To change the logging settings, edit the logging config file `<install location>/SenseNebulaConnector/src/main/resources/log4j2.xml`,
which is an [Apache Log4j configuration file.](https://logging.apache.org/log4j/2.x/manual/configuration.html). The logger 
name for each class is the class's fully qualified class name, *e.g.* "io.vantiq.extjsdk.ExtensionWebSocketClient".  

The project is configured with 2 daily rolling log files:
* `./logs/nebula.log`
* `./logs/raw_nebula_events.log`

These log files are meant for capturing debugging details, can be turned off in production mode, especially `raw_nebula_events.log` which will capture raw event messages from SenseNebula box in JSON format. These can be used for simulating message playbacks for debugging purposes.

## Server Config File <a name="serverConfig" id="serverConfig"></a>
(Please read the [SDK's server config documentation](../extjsdk/README.md#serverConfig) first.)

### Vantiq Options
*   **authToken**: Required. The authentication token to connect with. These can be obtained from the namespace admin.
*   **sources**: Required. A comma separated list of the sources to which you wish to connect. Any whitespace will be
    removed when read.
*   **targetServer**: Required. The Vantiq server hosting the sources.

# Setting Up Your Vantiq Source <a name="vantiq" id="vantiq"></a>

## Source Configuration <a name="sourceConfig" id="sourceConfig"></a>

To set up the Source in the Vantiq system, you will need to add a Source to your project. Please check the [Prerequisites](#pre) 
to make sure you have properly added a Source Implementation definition to your Vantiq namespace. Once this is complete, 
you can select SenseNebulaConnector (or whatever you named your Source Implementation) as the Source Type. You will then need 
to fill out the Source Configuration Document.

The Configuration document may look similar to the following example:

    {
        "senseNebulaConfig": {
            "general": {
                "FR_dbName": "Visitor",
                "senseNebula_URL": "http://localhost:8080/",
                "password": "xxxxx",
                "username": "xxxxx"
            }
        }
    }

### Options Available for senseNebulaConfig
**Note:** The "senseNebulaConfig" and "general" portions must be included in the source configuration, but they can be left
empty.
*   **FR_dbName**: Required. The name of facial recognition database in SenseNebula Edge Box that is used for holding new records and performing facial matches.
*   **senseNebula_URL**: Required. URL to reach SenseNebula Edge Box
*   **password**: Required. 
*   **username**: Required.


## Select Statements

The current implementation allows Select statements to be used for adding a new facial recognition record onto SenseNebula Edge Box. 

The following shows the example using a Vail Select Statement:
```
    Var result = SELECT * FROM SOURCE SenseNebulaSource WITH 
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
