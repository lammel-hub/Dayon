#!/bin/sh
if [ ! "$(whoami)" = "root" ]; then
	echo "This script must be run as super user - e.g. 'sudo sh setup.sh'"
	exit 77
fi

SHORTCUT_DIR=/usr/share/applications
if [ ! -d "$SHORTCUT_DIR" ]; then
	echo "Fatal: Unknown environment - '/usr/share/applications' not found."
	exit 78
fi

which java >/dev/null
if [ ! $? -eq 0 ]; then
	echo "***************************************************************************************"
	echo "* Fensterkitt Support App requires a Java Runtime Environment (JRE) to run.           *"
	echo "* You will have to install a JRE afterwards - e.g. 'sudo apt-get install default-jre' *"
	echo "***************************************************************************************"
fi

INSTALL_DIR=$(dirname "$0")
if [ "$INSTALL_DIR" = "." ]; then
	INSTALL_DIR=$(pwd)
fi
chmod +x "${INSTALL_DIR}"/dayon*sh

cat <<EOF > /usr/share/applications/dayon_assisted.desktop
[Desktop Entry]
Name=Fensterkitt Support App
Version=11.0
Exec=${INSTALL_DIR}/dayon_assisted.sh
Comment=Request remote assistance
Comment[de]=Remotesupport erbitten
Comment[es]=Solicitar asistencia remota
Comment[fr]=Demander assistance à distance
Comment[it]=Richiedi assistenza remota
Comment[ru]=Запросить удаленную помощь
Comment[tr]=Uzaktan yardım isteyin
Comment[zh]=请求远程协助
Keywords=remote;support;get help
Icon=${INSTALL_DIR}/dayon.png
Type=Application
Terminal=false
StartupNotify=true
Encoding=UTF-8
Categories=RemoteAccess;Network;
EOF

echo "Fensterkitt Support App setup finished successfully - happy sessions!"
