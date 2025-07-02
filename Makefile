JAVAC = javac
JAVA  = java

SRC   = $(wildcard src/*.java)
BUILD = build

.PHONY: all clean server client restart

all: $(BUILD)
	@echo "Compiling all sources…"
	$(JAVAC) -d $(BUILD) $(SRC)

$(BUILD):
	mkdir -p $(BUILD)

server: all
	@echo "Starting server on port 12345…"
	$(JAVA) -cp $(BUILD) ChatServer 12345

client: all
	@echo "Starting client…"
	$(JAVA) -cp $(BUILD) ChatClient localhost 12345

clean:
	rm -rf $(BUILD)/*.class

restart:
	@echo "Killing any ChatServer/ChatClient…"
	pkill -f "ChatServer 12345" || true
	pkill -f "ChatClient localhost 12345" || true
	$(MAKE) server &
	sleep 1
	$(MAKE) client &
