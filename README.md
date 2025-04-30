# mc_missile
This is a [Fabric](https://wiki.fabricmc.net/start) Minecraft mod.

### Development environment
- [Install Docker](https://docs.docker.com/engine/install).
- Create the dev container in this directory with: `sudo docker run --net host -ti --name mc_missile_java -v ./:/home/gradle/mc_missile -p 25565:25565 --entrypoint /bin/bash gradle`
- When you leave that shell start the container again with: `sudo docker start mc_missile_java`
- Now you can enter it with: `sudo docker exec -ti mc_missile_java /bin/bash`

### Decompile Minecraft
- run these commands in the mc_missile directory in the Docker container.
- `./gradlew genSources`
- `jar xf ../.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-abba00472f/1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2/minecraft-merged-abba00472f-1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2-sources.jar com /net`
- `jar xf ../.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-abba00472f/1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2/minecraft-merged-abba00472f-1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2.jar assets/ data /`

### Gradle
- run these commands in the mc_missile directory in the Docker container.
- `./gradlew validateAccessWidener`
- `./gradlew runServer`

### Formatting
- Download [google-java-format](https://github.com/google/google-java-format/releases)
- run `find ./src -name '*.java' -type f -print0 | xargs -0 java -jar ~/dwn/google-java-format-1.26.0-all-deps.jar --aosp -r`
