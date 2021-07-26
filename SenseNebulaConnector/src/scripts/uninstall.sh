#!/bin/sh
## Script to uninstall this app as a service in systemd

echo "Uninstall SenseNebulaExtSrc service..."
sudo systemctl stop SenseNebulaExtSrc
sudo systemctl disable SenseNebulaExtSrc

echo "Removing service file..."
sudo rm /etc/systemd/system/SenseNebulaExtSrc.service

echo "Removing start script from /usr/bin..."
sudo rm /usr/bin/start_SenseNebulaExtSrc_app.sh

read -p 'Do you want to remove the startup script locally from here? [Y/N]: ' removefile
echo

if [ $removefile = 'Y' ] || [ $removefile = 'y' ]
then
    echo "Removing startup script from local ...."
    rm start_SenseNebulaExtSrc_app.sh
else
    echo "Keeping local startup script..."
fi
