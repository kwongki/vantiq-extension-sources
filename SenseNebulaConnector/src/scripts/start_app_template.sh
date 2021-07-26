#!/bin/sh

export SCRIPT_DIR=%working_dir%
cd $SCRIPT_DIR

./bin/SenseNebulaConnector server.config
