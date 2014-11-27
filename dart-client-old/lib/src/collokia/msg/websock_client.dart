part of msg_client;

typedef void HandleMessage(Message);
typedef void HandleOpen(ConnectionMetadata);
typedef void HandleClose(WebSocketsClient);
typedef void HandleError(Event);
typedef ConnectionMetadata MetadataRef();

void doNothingOnMessage(Message message) {}
void doNothingOnOpen(ConnectionMetadata metadata) {}
void doNothingOnClose(WebSocketsClient client) {}
void doNothingOnError(Event error) {}

const Duration DEFAULT_PING_INTERVAL = const Duration(seconds: 10);
const Duration DEFAULT_IDLE_TIMEOUT = const Duration(seconds: 30);
const Duration DEFAULT_SEND_TIMEOUT = const Duration(seconds: 5);
const Duration DEFAULT_RESPOND_TIMEOUT = const Duration(seconds: 10);

const Duration DURATION_ZERO = const Duration();
const Duration RETRY_DURATION = const Duration(milliseconds: 200);

class Message {

  final MetadataRef _metadata;
  final HandleMessage _doSend;
  final MessageEnvelope envelope;
  final Duration sendTimeout;
  final Duration respondTimeout;
  final HandleMessage onSendFail;
  final HandleMessage onResponse;
  final HandleMessage onRespondFail;
  final UUID id;
  final Uint8ClampedList body;
  final bool needsResponse;

  Message(
      MetadataRef metadata,
      HandleMessage doSend,
      MessageEnvelope envelope, {
      Duration sendTimeout: DEFAULT_SEND_TIMEOUT,
      Duration respondTimeout: DEFAULT_RESPOND_TIMEOUT,
      HandleMessage onSendFail,
      HandleMessage onResponse,
      HandleMessage onRespondFail}) :
      _metadata = metadata,
      _doSend = doSend,
      envelope = envelope,
      sendTimeout = sendTimeout,
      respondTimeout = respondTimeout,
      onSendFail = onSendFail,
      onResponse = onResponse,
      onRespondFail = onRespondFail,
      id = envelope.id,
      body = envelope.body,
      needsResponse = envelope.needsResponse;


  Message createMessage(
      Uint8ClampedList body, {
      String address: "",
      Duration sendTimeout: DEFAULT_SEND_TIMEOUT,
      Duration respondTimeout: DEFAULT_RESPOND_TIMEOUT,
      HandleMessage onSendFail: doNothingOnMessage,
      HandleMessage onResponse: doNothingOnMessage,
      HandleMessage onRespondFail: doNothingOnMessage}) {
  bool isManualAddress = address.isNotEmpty;
  bool needsResponse = onResponse != doNothingOnMessage;
  return new Message(
      _metadata,
      _doSend,
      new MessageEnvelope(
          new UUID.randomUuid(),
          MessageAction.SEND,
          isManualAddress: isManualAddress,
          needsResponse: needsResponse,
          manualAddress: address,
          returnAddress: (needsResponse ? _metadata().replyAddress : new UUID()),
          body: body),
      sendTimeout: sendTimeout,
      respondTimeout: respondTimeout,
      onSendFail: onSendFail,
      onResponse: onResponse,
      onRespondFail: onRespondFail);
  }

  Message createResponse(
      Uint8ClampedList body, {
      Duration sendTimeout: DEFAULT_SEND_TIMEOUT,
      Duration respondTimeout: DEFAULT_RESPOND_TIMEOUT,
      HandleMessage onSendFail: doNothingOnMessage,
      HandleMessage onResponse: doNothingOnMessage,
      HandleMessage onRespondFail: doNothingOnMessage}) {
    bool needsResponse = onResponse != doNothingOnMessage;
    return new Message(
        _metadata,
        _doSend,
        new MessageEnvelope(
            new UUID.randomUuid(),
            MessageAction.SEND,
            isResponse: true,
            needsResponse: needsResponse,
            address: envelope.returnAddress,
            respondId: envelope.id,
            returnAddress: (needsResponse ? _metadata().replyAddress : new UUID()),
            body: body),
        sendTimeout: sendTimeout,
        respondTimeout: respondTimeout,
        onSendFail: onSendFail,
        onResponse: onResponse,
        onRespondFail: onRespondFail);
  }

  void send() {
    _doSend(this);
  }
}

class WebSocketsClient {

  final String _endpoint;
  final Duration _pingInterval;
  final Duration _idleTimeout;
  final HandleMessage _onMessage;
  final HandleOpen _onOpen;
  final HandleClose _onClose;
  final HandleError _onError;

  WebSocket _ws;
  MessageHandlingService _handlingService;
  ConnectionMetadata _metadata;
  Message _defaultMessage;
  DateTime _lastMessageTime;
  WebSocketsClient _client;
  bool _closed;
  Timer _connectTimeout;

  WebSocketsClient(
      String endpoint, {
      Duration pingInterval: DEFAULT_PING_INTERVAL,
      Duration idleTimeout: DEFAULT_IDLE_TIMEOUT,
      HandleMessage onMessage: doNothingOnMessage,
      HandleOpen onOpen: doNothingOnOpen,
      HandleClose onClose: doNothingOnClose,
      HandleError onError: doNothingOnError}) :
      _endpoint = endpoint,
      _pingInterval = pingInterval,
      _idleTimeout = idleTimeout,
      _onMessage = onMessage,
      _onOpen = onOpen,
      _onClose = onClose,
      _onError = onError {
    _defaultMessage = new Message(_getMetadata, _sendMessage, new MessageEnvelope(new UUID(), MessageAction.SEND));
    var now = new DateTime.now();
    _metadata = new ConnectionMetadata();
    _lastMessageTime = now;
    _client = this;
    _closed = false;
    _handlingService = new MessageHandlingService();
    _startPingTask();
  }

  Message createMessage(
      Uint8ClampedList body, {
      String address: "",
      Duration sendTimeout: DEFAULT_SEND_TIMEOUT,
      Duration respondTimeout: DEFAULT_RESPOND_TIMEOUT,
      HandleMessage onSendFail: doNothingOnMessage,
      HandleMessage onResponse: doNothingOnMessage,
      HandleMessage onRespondFail: doNothingOnMessage}) {
    return _defaultMessage.createMessage(
        body,
        address: address,
        sendTimeout: sendTimeout,
        respondTimeout: respondTimeout,
        onSendFail: onSendFail,
        onResponse: onResponse,
        onRespondFail: onRespondFail);
  }

  Message _createCustomMessage(
      MessageEnvelope envelope, {
      Duration sendTimeout: DEFAULT_SEND_TIMEOUT,
      Duration respondTimeout: DEFAULT_RESPOND_TIMEOUT,
      HandleMessage onSendFail: doNothingOnMessage,
      HandleMessage onResponse: doNothingOnMessage,
      HandleMessage onRespondFail: doNothingOnMessage}) {
    return new Message(
        _getMetadata,
        _sendMessage,
        envelope,
        sendTimeout: sendTimeout,
        respondTimeout: respondTimeout,
        onSendFail: onSendFail,
        onResponse: onResponse,
        onRespondFail: onRespondFail);
  }

  void _sendInitMessage() {
    _createCustomMessage(new MessageEnvelope(new UUID.randomUuid(), MessageAction.INIT, needsResponse: true),
        onSendFail: (Message message) {
          message.send();
        },
        onRespondFail: (Message message) {
          message.send();
        },
        onResponse: (Message message) {
          if (_connectTimeout != null) {
            _connectTimeout.cancel();
            _connectTimeout = null;
          }
          _metadata = new ConnectionMetadata.fromBytes(message.body);
          _onOpen(_metadata);
        }).send();
  }

  void _sendPingMessage() {
    _createCustomMessage(new MessageEnvelope(new UUID.randomUuid(), MessageAction.PING, needsResponse: true)).send();
  }

  void _sendPongMessage(UUID id) {
    _sendBytes(new MessageEnvelope(id, MessageAction.PONG).toBytes());
  }

  void _sendSentMessage(UUID id) {
    _sendBytes(new MessageEnvelope(id, MessageAction.SENT).toBytes());
  }

  ConnectionMetadata _getMetadata() {
    return _metadata;
  }

  void _sendMessage(Message message) {
    _sendMessageWithDeadline(message, new DateTime.fromMillisecondsSinceEpoch(new DateTime.now().millisecondsSinceEpoch + message.sendTimeout.inMilliseconds));
  }

  void _sendMessageWithDeadline(Message message, DateTime deadline) {
    var localWs = _ws;
    if (localWs == null || localWs.readyState != WebSocket.OPEN) {
      DateTime now = new DateTime.now();
      int remainingTime = deadline.millisecondsSinceEpoch - now.millisecondsSinceEpoch;
      if (remainingTime > 0) {
        Duration retryTime = RETRY_DURATION;
        if (remainingTime < retryTime.inMilliseconds) {
          retryTime = new Duration(milliseconds: remainingTime);
        }
        new Timer(retryTime, () => _sendMessageWithDeadline(message, deadline));
      } else {
        message.onSendFail(message);
      }
    } else {
      try {
        _handlingService.putHandler(message);
        localWs.sendTypedData(message.envelope.toBytes());
      } catch(e) {
        message.onSendFail(message);
      }
    }
  }

  void _sendBytes(Uint8ClampedList bytes) {
    var localWs = _ws;
    if (localWs != null && localWs.readyState == WebSocket.OPEN) {
      localWs.sendTypedData(bytes);
    }
  }

  void close() {
    _closed = true;
    try {
      _handlingService.close();
    } finally {
      if (_ws != null) {
        _ws.close();
      }
    }
  }

  WebSocket _start() {
    var ws = new WebSocket(_endpoint);
    ws.binaryType = "arraybuffer";
    ws.onOpen.listen((Event event) {
      var now = new DateTime.now();
      _lastMessageTime = now;
      _ws = ws;
      _sendInitMessage();
    });
    ws.onMessage.listen((MessageEvent event) {
      _lastMessageTime = new DateTime.now();
      Uint8ClampedList messageBytes = null;
      var data = event.data;
      if (data is ByteBuffer) {
        ByteBuffer buffer = data;
        messageBytes = buffer.asUint8ClampedList();
      }
      if (messageBytes != null) {
        var envelope = new MessageEnvelope.fromBytes(messageBytes);
        switch (envelope.action) {
          case MessageAction.META:
            _handlingService.handleResponse(new Message(_getMetadata, _sendMessage, envelope));
            break;
          case MessageAction.PING:
            _sendPongMessage(envelope.id);
            break;
          case MessageAction.PONG:
            _handlingService.handleResponse(new Message(_getMetadata, _sendMessage, envelope));
            break;
          case MessageAction.SEND:
            _sendSentMessage(envelope.id);
            Message message = new Message(_getMetadata, _sendMessage, envelope);
            if (!message.envelope.isResponse || !_handlingService.handleResponse(message)) {
              _onMessage(message);
            }
            break;
          case MessageAction.SENT:
            _handlingService.markSent(envelope.id);
            break;
        }
      }
    });
    ws.onClose.listen((CloseEvent event) {
      _onClose(_client);
    });
    ws.onError.listen((Event event) {
      _onError(event);
    });
    return ws;
  }

  void _startPingTask() {
    new Timer.periodic(new Duration(milliseconds: 1000), _pingTick);
  }

  void _pingTick(Timer timer) {
    try {
      if (_closed) {
        timer.cancel();
        return;
      }
      var lastTime = _lastMessageTime;
      var now = new DateTime.now().millisecondsSinceEpoch;
      if (lastTime.isBefore(new DateTime.fromMillisecondsSinceEpoch(now - _pingInterval.inMilliseconds)) && _ws != null && _connectTimeout == null) {
        _sendPingMessage();
      }
      var timedOut = lastTime.isBefore(new DateTime.fromMillisecondsSinceEpoch(now - _idleTimeout.inMilliseconds));
      var localSock = _ws;
      if (localSock == null || timedOut || localSock.readyState != WebSocket.OPEN) {
        if (_connectTimeout == null) {
          if (localSock != null && localSock.readyState == WebSocket.OPEN) {
            localSock.close();
          }
          Wrapper<WebSocket> wrapper = new Wrapper<WebSocket>(null);
          _connectTimeout = new Timer(new Duration(milliseconds: 10000), () {
            if (wrapper.value != null) {
              wrapper.value.close();
            }
            _connectTimeout = null;
          });
          wrapper.value = _start();
        }
      }
    } catch (ignore) {
    }
  }
}
