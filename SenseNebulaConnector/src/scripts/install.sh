#!/bin/sh
#Script to install this app as a service in systemd
echo "Installing SenseNebulaConnector as a systemd service"

sed "s:%working_dir%:$(pwd):g" start_app_template.sh > start_SenseNebulaExtSrc_app.sh

echo "Creating service file ..."
cat > SenseNebulaExtSrc.service << EOL
[Unit]
Description=Sense Nebula Connector Service.
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=$(pwd)
User=$(whoami)
Type=simple
ExecStart=/bin/bash /usr/bin/start_SenseNebulaExtSrc_app.sh

[Install]
WantedBy=multi-user.target
EOL

cat SenseNebulaExtSrc.service

echo "Copying startup script to /usr/bin..."
sudo cp start_SenseNebulaExtSrc_app.sh /usr/bin/start_SenseNebulaExtSrc_app.sh
sudo chmod +x /usr/bin/start_SenseNebulaExtSrc_app.sh
echo "Copying service file to /etc/systemd/system..."
sudo cp SenseNebulaExtSrc.service /etc/systemd/system/SenseNebulaExtSrc.service
echo "Enable SenseNebulaExtSrc service..."
sudo systemctl enable SenseNebulaExtSrc

echo "Installation success!"