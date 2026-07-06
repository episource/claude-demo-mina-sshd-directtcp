# Mina SSHD Direct-TCPIP Console Demo

An educational, self-contained demonstration of the SSH `direct-tcpip` channel type using
[Apache Mina SSHD](https://mina.apache.org/sshd-project/). An embedded SSH server and client
run in a single JVM: the client streams console input over a `direct-tcpip` channel, and the
server echoes each line back with a `!` prefix. The purpose is to demonstrate how a
`direct-tcpip` SSH channel can be implemented without the need to do actual port forwarding.

> **Note:** This is a test project for exploring and evaluating **Claude Code** usage.
> It is not intended for production use.

Full requirements are documented in [docs/requirements.md](docs/requirements.md).

## Build & Run

```
mvn clean      # prepare a clean build
mvn package    # compile, test and package the fat jar
mvn test       # run tests only
```

Run the packaged demo:

```
java -jar target/sshd-direct-tcpip-demo-jar-with-dependencies.jar
```

Type text and press Enter to send it to the server; type `exit` to quit.
