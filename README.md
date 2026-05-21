# Secure Chat

A small Java terminal chat application with authenticated users and file transfer, served over TLS 1.3.

This is the hardened version of a university Java project. The rebuild fixes several real security issues from the original and packages the result for Docker delivery with CI.

[![CI](https://github.com/Aan4s/secure-chat/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Aan4s/secure-chat/actions/workflows/ci.yml)
![CodeQL](https://img.shields.io/badge/SAST-CodeQL-green)
![Java](https://img.shields.io/badge/Java-21-orange)

## Features

- TLS 1.3 transport between client and server
- BCrypt password authentication (cost factor 12)
- One-to-one chat between authenticated online users
- File transfer (capped at 100 MiB, base64-chunked, filenames sanitized)
- Dockerized server with non-root runtime user
- GitHub Actions CI with CodeQL static analysis

## What was wrong before, and what was fixed

| Issue in the original code | Risk | Fix |
|---|---|---|
| `Cipher.getInstance("AES")` silently defaulted to ECB mode | Repeated plaintext blocks → identical ciphertext (the "ECB penguin") | Removed application-layer encryption; transport is now TLS 1.3 with built-in AEAD |
| Hardcoded encryption key `"mySuperSecretKey"` in source | Anyone who reads the repo can decrypt all traffic | Removed entirely; TLS uses a per-deployment keystore |
| Passwords hashed with plain SHA-256, no salt | GPUs do billions of SHA-256/sec — trivially brute-forceable | Replaced with BCrypt (cost 12, ~250 ms/guess on modern CPUs); salt embedded per-hash |
| Filename from sender used directly in `new File(dir, name)` | Path traversal: a malicious peer could write `../etc/anything` | Filenames are sanitized to a basename with restricted character set |
| File size parsed as `Long` with no upper bound | DoS: attacker sends `Long.MAX_VALUE` to exhaust disk | Files capped at 100 MiB; oversized transfers refused |
| Mixed text lines and raw bytes on the same socket | Buffering hazards, framing bugs | File data sent as base64 inside the text protocol — never mix the two |

## Run locally

### Prerequisites
- Java 21+
- Maven 3.9+
- The `keytool` command (ships with the JDK)

### Steps

```sh
# 1. Generate a self-signed certificate (one-time)
./scripts/gen-certs.sh

# 2. Build
mvn package

# 3. Start the server (one terminal)
java -jar target/secure-chat.jar server

# 4. Start a client (another terminal — repeat for more clients)
java -jar target/secure-chat.jar client
```

The default keystore password is `changeit`. For anything beyond a local demo, set
`SECURECHAT_KEYSTORE_PASSWORD` before running the cert script and the server.

## Run with Docker

```sh
./scripts/gen-certs.sh
docker compose up --build
```

The server runs in the container; clients run on the host with `java -jar`.

## Usage

After login the server prints a welcome message with the available commands.

### Sending messages

```
bob:hello there
```

(`bob` is the recipient's username. They must be online.)

### Sending a file

```
@file:bob:/home/me/Documents/report.pdf
```

- The path is the **local** path on your machine.
- Max size: 100 MiB.
- The filename is sanitized — non-alphanumeric characters become `_`, any path
  prefix is stripped — so `../etc/passwd` becomes just `passwd`.

### Where received files land

On the **receiver's** side, the file is saved to:

```
<directory where the receiver started the client>/downloads/<filename>
```

When a file arrives the client prints the absolute path, e.g.:

```
=== File received from alice ===
    Saved to: /home/bob/secure-chat/downloads/report.pdf
```

If a file with the same name already exists in `downloads/`, the new one is saved
with a timestamp prefix (e.g. `1734567890123_report.pdf`).

### Commands

| Command | What it does |
|---|---|
| `<recipient>:<message>` | Send a chat message |
| `@file:<recipient>:<path>` | Send a file |
| `/who` | List currently online users |
| `/help` | Show this list again |
| `/quit` or `bye` | Disconnect |

## Environment variables

| Variable | Default | Used by |
|---|---|---|
| `SECURECHAT_PORT` | `8888` | server |
| `SECURECHAT_KEYSTORE` | `certs/server.p12` | server |
| `SECURECHAT_KEYSTORE_PASSWORD` | `changeit` | server, cert script |
| `SECURECHAT_USERS_FILE` | `users.txt` | server |
| `SECURECHAT_TRUSTSTORE` | `certs/client-truststore.p12` | client |
| `SECURECHAT_TRUSTSTORE_PASSWORD` | `changeit` | client |

## Project layout

```
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── scripts/gen-certs.sh
├── .github/workflows/         # CI + CodeQL
├── src/main/java/org/example/
│   ├── Main.java
│   ├── TlsContextFactory.java
│   ├── clients/Client.java
│   ├── servers/Server.java
│   ├── servers/CommunicationHandler.java
│   ├── servers/AuthManager.java
│   └── util/Filenames.java
└── src/test/java/org/example/
    ├── servers/AuthManagerTest.java
    └── util/FilenamesTest.java
```

## Honest scope and limitations

This is a portfolio / learning project. Known limitations that would matter in production:

- **Self-signed certificate** — fine for a demo, not for the public internet. Real deployments would use a CA-signed cert.
- **No rate limiting on login** — a brute-force attacker could still try many passwords. BCrypt's cost slows them down but doesn't stop them. Adding per-IP rate limiting would be a small follow-up.
- **Server can read all messages** — same trust model as IRC over TLS or Discord. True end-to-end encryption between users (server cannot decrypt) would require a separate protocol like Signal — out of scope here.
- **No message persistence** — offline users miss messages sent to them.

## License

MIT — see [`LICENSE`](./LICENSE).
