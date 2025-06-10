# mc_missile
This is a [Fabric](https://wiki.fabricmc.net/start) Minecraft mod.
It allows all players to program guided missiles and fire them from a [crossbow](https://minecraft.wiki/w/Crossbow) or [dispenser](https://minecraft.wiki/w/Dispenser).

A missile is built from components, each with a price tag and effects on the missile.

### Flight Dynamics
Like the player (including my brother) the missile is an [entity](https://minecraft.wiki/w/Entity) in Minecraft.
The missile has
-   a position $p = \begin{pmatrix} p_1 \\ p_2 \\ p_3 \end{pmatrix} \in \mathbb{R}^3$ (with the elevation $$p_2$$),
-   velocity $v \in \mathbb{R}^3$,
-   pitch $\theta \in [-90, 90]$ and
-   yaw $\psi \in [-180, 180]$.

    These angles are in degrees and there's no roll.
    Additionally, they are the negated [angles](https://minecraft.wiki/w/Rotation) displayed in the [Mincraft F3 Menu](https://minecraft.wiki/w/Debug_screen).
    That's because projectiles have flipped headings for some reason:
    $\theta=90$ is up, $\theta=-90$ down and $\psi \in \{-180, 180\}$ is north, $\psi = 90$ east, $\psi = 0$ south and $\psi = -90$ west.

Minecraft updates the missile's state every tick (20 times a second).
The update in tick $t \in \mathbb{N}_0$ is separated into three stages:
1.  Receive the control input:
    The missile's only input method is an unrealisticly beefy [control moment gyroscope](https://en.wikipedia.org/wiki/Control_moment_gyroscope).
    So the player's guidance code produces a requested change in pitch $\theta_{in}$ and yaw $\psi_{in}$.
    It has no direct control over the missiles position, velocity, or thrust — only the rotation.

2.  Update the missile's state:
    The gyroscope is powerful but not overly powerful.
    When the control input is too large (larger than $M_r$ defined by the airframe), it is scaled down linearly:
    $$
    \begin{aligned}
    l &= \sqrt{\theta_{in}^2 + \psi_{in}^2} \\
    \begin{pmatrix} \theta_{in} \\ \psi_{in} \end{pmatrix} 
      &\rightarrow \max{\left\{\frac{M_r}{l}, 1\right\}}
       \begin{pmatrix} \theta_{in} \\ \psi_{in} \end{pmatrix}.
    \end{aligned}
    $$
    With this, Minecraft applies the adjusted control input plus some random noise.
    $$
    \begin{aligned}
    \theta &\rightarrow \theta + \theta_{in} + N_r \\
    \psi   &\rightarrow \psi + \psi_{in} + N_r \\
    \end{aligned}
    $$
    each $$N_r$$ is normally distributed noise dependent on the air frame used by the missile.
    So you don't have perfect control over the missiles rotation, there is always some variance.

    Now the acceleration $a \in \mathbb{R}^3$ can be calculated from the rotation vector $r \in \mathbb{R}^3$, the current thrust $T(t) \in [0, \infty)$ and gravity $g = \begin{pmatrix} 0 \\ -\|g\| \\ 0 \end{pmatrix} \in \mathbb{R}^3$.
    $$
    \begin{aligned}
    r & =
    \begin{pmatrix}
        \sin(\psi) \cos(\theta) \\
        \sin(\theta) \\
        \cos(\psi) \cos(\theta)
    \end{pmatrix} \\
    a & = \begin{pmatrix} a_1 \\ a_2 \\ a_3 \end{pmatrix} \\
      & = \left(T(t) + N_T \right) \cdot r + g
    \end{aligned}
    $$
    $$N_T$$ is normally distributed noise and like the thrust function $T$ defined by the rocket motor.

    Lastly, the velocity and position are updated with the airframe defined drag $d \in [0, 1)$.
    $$
    \begin{aligned}
    v &\rightarrow (1 - d) \cdot (v + a) \\
    p &\rightarrow p + v
    \end{aligned}
    $$

3.  Now that the missile's state has been updated Minecraft sends that state to the player's guidance code.
    All these values contain some variance, too — depending on the missile's [inertial measurement unit](https://en.wikipedia.org/wiki/Inertial_measurement_unit).
    The guidance server has a little less than 50ms to send the rotation change for the next tick.

In the first tick ($t=0$), the missile doesn't have guidance input yet and thus flies straight ahead with the velocity and rotation of the shooter.
No variance is applied here.

Disclaimer:
As you can see the flight dynamics of Minecraft missiles don't have anything to do with *real* missiles.
There are no aerodynamic aspects simulated at all, for example.
All we do is magically rotate a rock in vacuum.
Thus the guidance code explained in this article can only be applied to the toy world that is Minecraft and nothing else.
Still, it'll be fun!


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

### Deploy
- `cp ./env.sh.example ./env.sh` and enter your modrinth token (only do this once)
- `source ./env.sh`
- `./gradlew modrinth`

### Formatting
- Download [google-java-format](https://github.com/google/google-java-format/releases)
- run `find ./src -name '*.java' -type f -print0 | xargs -0 java -jar ~/dwn/google-java-format-1.26.0-all-deps.jar --aosp -r`
