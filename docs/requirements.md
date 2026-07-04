# Requirements: Mina SSHD Direct-TCPIP Console Demo

## 1. Overview

This project is an educational, self-contained demonstration of the SSH `direct-tcpip`
channel type using [Apache Mina SSHD](https://mina.apache.org/sshd-project/). It runs an
embedded SSH server and an embedded SSH client in a single JVM, has the client open a
`direct-tcpip` channel to the server, and streams console input over that channel. The server
echoes each received chunk back over the same channel with a literal `!` prepended, and the
client prints whatever it gets back to its console. It is intended for developers who want to
see the `direct-tcpip` channel-open handshake and data-flow mechanics without the added
complexity of a real TCP relay.

## 2. Goals / Non-Goals

**Goals**
- Demonstrate the SSH `direct-tcpip` channel-open handshake (target host/port, originator
  host/port fields).
- Demonstrate streaming data over an open channel and local flow-control window
  replenishment.
- Keep the demo runnable with a single command and no external network dependencies.

**Non-Goals**
- Performing real TCP port forwarding/relaying.
- Production-grade authentication or security.
- Supporting multiple concurrent client sessions.

## 3. Functional Requirements

### 3.1 Embedded SSH server (`DemoServer`)
- Must listen on a configurable TCP port (currently hardcoded to `2222` in `App.java`).
- Must generate or load a persistent host key from a given file path
  (`SimpleGeneratorHostKeyProvider`, default path `demo-hostkey.ser`).
- Must accept authentication only from one specific RSA 4096 public key
  (`KeySetPublickeyAuthenticator`), configured from a hardcoded demo private key constant in
  `App` — demo only; a real deployment would never embed a private key in source or run
  both peers in one JVM.
- Must reject standard SSH forwarding requests (`RejectAllForwardingFilter`), since real
  forwarding is out of scope for this demo.
- Must register a standard session channel factory (`ChannelSessionFactory`) for protocol
  completeness, plus a custom `direct-tcpip` channel factory (`EchoChannelFactory`).
- Must be closeable (`AutoCloseable`) and stop the underlying `SshServer` on close.

### 3.2 Embedded SSH client (`App`)
- Must connect to the embedded server on `127.0.0.1` at the server's listening port.
- Must authenticate using an RSA 4096 public-key identity, loaded at startup from a
  bundled PEM resource file (`src/main/resources/demo-client-key.pem`).
- Must open a `direct-tcpip` channel (`ChannelDirectTcpip`) using placeholder local and
  target addresses; these addresses are never used to open a real socket on either end.
- Must read lines from standard input and write each line (UTF-8 encoded, newline
  terminated) into the channel's outbound stream.
- Must terminate the console-input loop (`pumpConsoleToChannel`) and close the
  `direct-tcpip` channel when the user enters `exit` (case-insensitive, leading/trailing
  whitespace ignored), without sending that line to the server.
- Must run a background thread that reads `ChannelDirectTcpip.getInvertedOut()` and prints
  each echoed response from the server to the console as it arrives (`ChannelDirectTcpip`
  feeds inbound channel data into that piped stream unconditionally, so `ClientChannel.setOut`
  has no effect for this channel type).
- Must authenticate and open the channel within a bounded timeout (currently 10 seconds).
- Must release client and session resources deterministically (try-with-resources) on exit.
- The client-facing logic (`App.runClient`, package-private) must take its console input and
  output as an `InputStream`/`PrintStream` parameter pair rather than hardcoding
  `System.in`/`System.out`, so it can be driven by an automated test; `App.main` supplies
  `System.in`/`System.out` and remains the only console-attached entry point.

### 3.3 Echo channel (`EchoServerChannel`, `EchoChannelFactory`)
- Must handle channel type `"direct-tcpip"` on the server side.
- On channel open, must parse and discard the standard direct-tcpip payload fields
  (host-to-connect, port-to-connect, originator IP, originator port) to keep the buffer
  well-formed, and log the requested target/originator for visibility.
- Must NOT open an outbound connection to the requested target host/port.
- On receiving channel data, must echo the received chunk back to the client over the same
  channel with a literal `!` prepended, sent via an outbound `direct-tcpip` data stream
  (`ChannelAsyncOutputStream`) that is flow-controlled against the channel's remote window.
- Must replenish the local flow-control window after consuming received data, so the peer
  continues sending.
- Must explicitly reject SSH extended data (`doWriteExtendedData` throws
  `UnsupportedOperationException`), since this channel type does not support it.

## 4. Non-Functional Requirements

- Must target Java 21 (LTS); no preview features or APIs introduced after Java 21.
- Must run entirely in a single JVM over loopback/in-process channels — no external network
  dependencies required to run the demo.
- Must log via SLF4J (`slf4j-simple`), configured through
  `src/main/resources/simplelogger.properties` (default level `warn`).
- Must build as a runnable fat jar via `maven-assembly-plugin`
  (`jar-with-dependencies` descriptor, `appendAssemblyId=true`), with
  `com.example.sshddemo.App` as the manifest main class.

## 5. Configuration / Constants

| Setting | Current value | Location | Notes |
|---|---|---|---|
| Server port | `2222` | `App.SERVER_PORT` | Hardcoded |
| Username | `demo` | `App.USERNAME` | Hardcoded |
| Client key | RSA 4096 key pair | `src/main/resources/demo-client-key.pem` | Hardcoded PKCS#8 PEM resource file, bundled into the jar; loaded once in `App.main` and shared between the client identity and the server's accepted-key set |
| Connect/auth timeout | 10 seconds | `App.TIMEOUT` | Hardcoded |
| Host key file | `demo-hostkey.ser` | `App.main` | Generated on first run if absent; gitignored |
| Log level | `warn` | `simplelogger.properties` | SLF4J simple logger default |

These values are demo-only and are not intended for production deployment.

## 6. Out of Scope / Explicit Exclusions

- No real TCP relaying/forwarding occurs on either the client or server side.
- No production-grade authentication (a single hardcoded demo key is accepted; no real key
  store, revocation, or per-user key management).
- No support for multiple concurrent client sessions.
- No persistence beyond the generated host key file.

## 7. Build & Run

Per the project's build conventions (see `CLAUDE.md`):

- `mvn clean` — prepare a clean build.
- `mvn package` — compile, test, and package the fat jar.
- `mvn test` — run tests only.

### 7.1 Automated tests

`PublicKeyAuthenticationTest` (JUnit 5) starts a real `DemoServer` on an ephemeral port and
verifies the public-key authenticator behaves as required:
- the hardcoded demo key pair (`App.loadClientKeyPair()`) is accepted;
- an unrelated, freshly generated RSA key pair is rejected;
- password authentication is rejected (no `PasswordAuthenticator` is registered at all).

`DirectTcpipEchoTest` (JUnit 5) starts a real `DemoServer` on an ephemeral port and drives
`App.runClient` through a piped `InputStream`/captured `PrintStream` (no real console) to prove
the echo round trip: each line sent over the `direct-tcpip` channel comes back prefixed with a
literal `!` and is printed to the captured output. To avoid a race where the console-reading
loop could close the channel before a pending echo arrives, the test only sends the next line
(and only sends `exit`) after the previous line's echo has actually appeared in the captured
output, polling with a bounded timeout rather than assuming fixed timing.

Run the packaged demo:

```
java -jar target/sshd-direct-tcpip-demo-jar-with-dependencies.jar
```

Type text and press Enter to send it to the server over the `direct-tcpip` channel; type
`exit` to quit.
