# Client-Server Chat System

Simple Client-Server Chatroom in Java supporting AI participation.

![image](https://github.com/user-attachments/assets/2b33fff0-ef23-4ea3-a90e-f251bc966e84)

Ported and adapted from my group's [Parallel and Distributed Computing](https://sigarra.up.pt/feup/en/ucurr_geral.ficha_uc_view?pv_ocorrencia_id=541893) GitLab repository.




## Project Overview

This project implements a *client-server chat system using TCP and SSL*, developed in Java. It supports:

- User authentication with secure session tokens
- Persistent rooms and real-time chat
- AI-assisted rooms using a local LLM (Llama3.2)
- Tolerance to temporary disconnections with automatic session recovery
- Concurrency handled via virtual threads and manual locks

## Requirements
-Java SE 21 or more recent;

-To support AI assisted rooms, Ollama running Llama3.2 is required;

## Ollama

1- Download and install ollama from the official website:  [https://ollama.com](https://ollama.com)


2- Run the command `ollama run llama3.2`

## Build & Run

From project root:

### Windows (Command Prompt)

```bat
javac -d build src\*.java
java -cp build ChatServer 12345
java -cp build ChatClient localhost 12345
```

### Linux/macOS

```bash
make
make server
make client 
```

**Group T06G10** members:

1. Tomás Esteves (up202205045@up.pt)
2. João Parada (up201405280@up.pt)
3. Gabriela Neto (up202004443@up.pt)
