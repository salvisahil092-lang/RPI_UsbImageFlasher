#1. Get the project into a repo

#Create a new GitHub repo (e.g. usb-flasher), unzip UsbFlasher-Phase1.zip locally, push its contents, or just drag-drop the extracted folder into a new repo via GitHub's web upload.
#Open that repo → Code → Codespaces → Create codespace on main.


#2. Install Java + Android SDK in the Codespace terminal

sudo apt-get update && sudo apt-get install -y openjdk-17-jdk unzip
java -version   # confirm 17

mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
curl -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip cmdline-tools.zip && mv cmdline-tools latest

echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

#3. Accept licenses and install build tools

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

#4. Generate the Gradle wrapper (I couldn't fetch it in my sandbox)

cd /workspaces/RPI_UsbImageFlasher  # your repo root
gradle wrapper --gradle-version 8.7

#5. Find where JDK 17 actually is

sudo update-alternatives --list java

#6. Pin Gradle to that JDK — add this line to gradle.properties in your repo root (not the app/ one, the top-level one):

echo "org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64" >> gradle.properties

#7. Also fix it for your shell so ./gradlew itself runs on 17:

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

#8. If step 1 shows JDK 17 isn't actually installed (only 25 shows up), run:

sudo apt-get install -y openjdk-17-jdk

#9. Build the debug APK

./gradlew assembleDebug


