part of msg_client;

List<int> writeByte(int value, List<int> output) {
  output.add(value & 0x000000FF);
  return output;
}

int readByte(List<int> input, Wrapper<int> cursor) {
  return input[cursor.value++] & 0x000000FF;
}

List<int> writeInt(int value, List<int> output) {
  writeByte(value >> 24, output);
  writeByte(value >> 16, output);
  writeByte(value >> 8, output);
  writeByte(value >> 0, output);
  return output;
}

int readInt(List<int> input, Wrapper<int> cursor) {
  return (readByte(input, cursor) << 24)
  | (readByte(input, cursor) << 16)
  | (readByte(input, cursor) << 8)
  | readByte(input, cursor);
}

List<int> writeBytes(List<int> value, List<int> output) {
  writeInt(value.length, output);
  value.forEach((it) => writeByte(it, output));
  return output;
}

Uint8ClampedList readBytes(List<int> input, Wrapper<int> cursor) {
  int length = readInt(input, cursor);
  Uint8ClampedList bytes = new Uint8ClampedList(length);
  for (int i = 0; i < length; i++) {
    bytes[i] = readByte(input, cursor);
  }
  return bytes;
}

List<int> writeUuid(UUID value, List<int> output) {
  value.toBytes().forEach((it) => writeByte(it, output));
  return output;
}

UUID readUuid(List<int> input, Wrapper<int> cursor) {
  Uint8ClampedList bytes = new Uint8ClampedList(16);
  for (int i = 0; i < 16; i++) {
    bytes[i] = readByte(input, cursor);
  }
  return new UUID.fromBytes(bytes);
}

List<int> writeUtf8(String value, List<int> output) {
  return writeBytes(encodeUtf8(value), output);
}

String readUtf8(List<int> input, Wrapper<int> cursor) {
  return decodeUtf8(readBytes(input, cursor));
}

List<int> writeBoolean(bool value, List<int> output) {
  return writeByte(value ? 1 : 0, output);
}

bool readBoolean(Uint8ClampedList input, Wrapper<int> cursor) {
  return readByte(input, cursor) == 1;
}

withDefault(value, def) {
  if (value == null) {
    return def;
  }
  return value;
}

class Wrapper<T> {

  T value;

  Wrapper(this.value);
}

class UUID {

  final Uint8ClampedList _bytes = new Uint8ClampedList(16);

  UUID();

  factory UUID.randomUuid() {
    UUID id = new UUID();
    new Uuid().v4(buffer: id._bytes);
    return id;
  }

  factory UUID.fromBytes(Iterable<int> bytes) {
    UUID id = new UUID();
    id._bytes.setAll(0, bytes);
    return id;
  }

  Uint8ClampedList toBytes() {
    return new Uint8ClampedList.fromList(_bytes);
  }
  
  String toString() {
    return new Uuid().unparse(_bytes);
  }
}

class MessageAction {
  static const int INIT = 0;
  static const int META = 1;
  static const int PING = 2;
  static const int PONG = 3;
  static const int SEND = 4;
  static const int SENT = 5;
}

class MessageEnvelope {

  final UUID id;
  final int action;
  final bool isManualAddress;
  final bool isResponse;
  final bool needsResponse;
  final String manualAddress;
  final UUID address;
  final UUID respondId;
  final UUID returnAddress;
  final Uint8ClampedList body;

  MessageEnvelope(
      UUID id,
      int action, {
      bool isManualAddress: false,
      bool isResponse: false,
      bool needsResponse: false,
      String manualAddress: "",
      UUID address,
      UUID respondId,
      UUID returnAddress,
      List<int> body}) :
      this.id = id,
      this.action = action,
      this.isManualAddress = isManualAddress,
      this.isResponse = isResponse,
      this.needsResponse = needsResponse,
      this.manualAddress = manualAddress,
      this.address = withDefault(address, new UUID()),
      this.respondId = withDefault(respondId, new UUID()),
      this.returnAddress = withDefault(returnAddress, new UUID()),
      this.body = new Uint8ClampedList.fromList(withDefault(body, new List<int>(0)));

  factory MessageEnvelope.fromBytes(Uint8ClampedList bytes, [Wrapper<int> cursor]) {
    if (cursor == null) {
      cursor = new Wrapper<int>(0);
    }
    UUID id = readUuid(bytes, cursor);
    int action = readByte(bytes, cursor);
    switch (action) {
      case MessageAction.INIT:
        return new MessageEnvelope(id, action, needsResponse: true);
      case MessageAction.META:
        Uint8ClampedList body = readBytes(bytes, cursor);
        return new MessageEnvelope(id, action, isResponse: true, respondId: id, body: body);
      case MessageAction.PING:
        return new MessageEnvelope(id, action, needsResponse: true);
      case MessageAction.PONG:
        return new MessageEnvelope(id, action, isResponse: true, respondId: id);
      case MessageAction.SEND:
        int booleans = readByte(bytes, cursor);
        bool isManualAddress = (4 & booleans) != 0;
        bool isResponse = (2 & booleans) != 0;
        bool needsResponse = (1 & booleans) != 0;
        String manualAddress = isManualAddress ? readUtf8(bytes, cursor) : "";
        UUID address = isResponse ? readUuid(bytes, cursor) : new UUID();
        UUID respondId = isResponse ? readUuid(bytes, cursor) : new UUID();
        UUID returnAddress = needsResponse ? readUuid(bytes, cursor) : new UUID();
        Uint8ClampedList body = readBytes(bytes, cursor);
        return new MessageEnvelope(id, action,
            isManualAddress: isManualAddress,
            isResponse: isResponse,
            needsResponse: needsResponse,
            manualAddress: manualAddress,
            address: address,
            respondId: respondId,
            returnAddress: returnAddress,
            body: body);
      case MessageAction.SENT:
        return new MessageEnvelope(id, action, isResponse: true, respondId: id);
      default:
        return new MessageEnvelope(id, action);
    }
  }

  List<int> writeTo(List<int> output) {
    writeUuid(id, output);
    writeByte(action, output);
    switch (action) {
      case MessageAction.SEND:
        writeByte((isManualAddress ? 4 : 0) | (isResponse ? 2 : 0) | (needsResponse ? 1 : 0), output);
        if (isManualAddress) {
          writeUtf8(manualAddress, output);
        }
        if (isResponse) {
          writeUuid(address, output);
          writeUuid(respondId, output);
        }
        if (needsResponse) {
          writeUuid(returnAddress, output);
        }
        writeBytes(body, output);
        break;
      case MessageAction.META:
        writeBytes(body, output);
        break;
    }
    return output;
  }

  Uint8ClampedList toBytes() {
    return new Uint8ClampedList.fromList(writeTo(new List<int>()));
  }
}

class ConnectionMetadata {

  final String directAddress;
  final UUID replyAddress;
  final bool isTrusted;

  ConnectionMetadata({String directAddress: "", UUID replyAddress, bool isTrusted: false}) :
      this.directAddress = directAddress,
      this.replyAddress = withDefault(replyAddress, new UUID()),
      this.isTrusted = isTrusted;


  factory ConnectionMetadata.fromBytes(Uint8ClampedList bytes, [Wrapper<int> cursor]) {
    if (cursor == null) {
      cursor = new Wrapper<int>(0);
    }
    String directAddress = readUtf8(bytes, cursor);
    UUID replyAddress = readUuid(bytes, cursor);
    bool isTrusted = readBoolean(bytes, cursor);
    return new ConnectionMetadata(directAddress: directAddress, replyAddress: replyAddress, isTrusted: isTrusted);
  }

  List<int> writeTo(List<int> output) {
    writeUtf8(directAddress, output);
    writeUuid(replyAddress, output);
    writeBoolean(isTrusted, output);
    return output;
  }

  Uint8ClampedList toBytes() {
    return new Uint8ClampedList.fromList(writeTo(new List<int>()));
  }
}
