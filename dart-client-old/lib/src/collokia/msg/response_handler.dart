part of msg_client;

class ResponseHandler {

  final Timer sendTimeoutFuture;
  final Timer respondTimeoutFuture;
  final HandleMessage handle;

  ResponseHandler(this.sendTimeoutFuture, this.respondTimeoutFuture, this.handle);
}

class MessageHandlingService {

  final Map<String, ResponseHandler> _responseMap = new Map<String, ResponseHandler>();
  bool _closed = false;

  MessageHandlingService() {
    _scheduleCleaner();
  }

  void close() {
    _closed = true;
  }

  void markSent(UUID id) {
    ResponseHandler handler = _responseMap[id.toString()];
    if (handler != null) {
      handler.sendTimeoutFuture.cancel();
    }
  }

  bool handleResponse(Message response) {
    ResponseHandler handler = _responseMap.remove(response.envelope.respondId.toString());
    if (handler != null) {
      handler.sendTimeoutFuture.cancel();
      if (handler.respondTimeoutFuture != null) {
        handler.respondTimeoutFuture.cancel();
      }
      HandleMessage handle = handler.handle;
      if (handle != null) {
        handle(response);
      }
      return true;
    }
    return false;
  }

  void putHandler(Message message) {
    if (message.needsResponse) {
      _putResponseHandler(message);
    } else {
      _putSendHandler(message);
    }
  }

  void _putSendHandler(Message message) {
    Timer sendTimeoutFuture = new Timer(message.sendTimeout, () {
      _responseMap.remove(message.id.toString());
      message.onSendFail(message);
    });
    _responseMap[message.id.toString()] = new ResponseHandler(sendTimeoutFuture, null, null);
  }

  void _putResponseHandler(Message message) {
    Timer sendTimeoutFuture = new Timer(message.sendTimeout, () {
      ResponseHandler handler = _responseMap.remove(message.id.toString());
      if (handler != null && handler.respondTimeoutFuture != null) {
        handler.respondTimeoutFuture.cancel();
      }
      message.onSendFail(message);
    });
    Timer respondTimeoutFuture = new Timer(message.respondTimeout, () {
      ResponseHandler handler = _responseMap.remove(message.id.toString());
      if (handler != null) {
        handler.sendTimeoutFuture.cancel();
      }
      message.onRespondFail(message);
    });
    _responseMap[message.id.toString()] = new ResponseHandler(sendTimeoutFuture, respondTimeoutFuture, message.onResponse);
  }

  void _scheduleCleaner() {
    new Timer.periodic(new Duration(seconds: 10), _cleanerTick);
  }

  void _cleanerTick(Timer timer) {
    if (_closed) {
      timer.cancel();
      return;
    }
    new Set.from(_responseMap.keys).forEach((String key) {
      ResponseHandler handler = _responseMap[key];
      if (handler != null) {
        if (!handler.sendTimeoutFuture.isActive) {
          if (handler.respondTimeoutFuture == null) {
            _responseMap.remove(key);
          } else if (!handler.respondTimeoutFuture.isActive) {
            _responseMap.remove(key);
          }
        }
      }
    });
  }
}

