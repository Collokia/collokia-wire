import 'dart:html';
import 'dart:async';
import 'package:msg_client/msg_client.dart';

WebSocketsClient client = null;
bool connecting = false;
Message lastReceivedMessage = null;

writeLog(String targetId, String msg) {
  var output = querySelector("#${targetId}");
  var date = new DateTime.now().toIso8601String();
  var text = "${date}: ${msg}\n";
  if (!output.text.isEmpty) {
    text = "${output.text}${text}";
  }
  output.text = text;
  output.parent.scrollTop = output.clientHeight;
}

String toString(MessageEnvelope envelope) {
  return """               id: ${envelope.id.toString()}
           action: ${envelope.action}
  isManualAddress: ${envelope.isManualAddress}
       isResponse: ${envelope.isResponse}
    needsResponse: ${envelope.needsResponse}
    manualAddress: ${envelope.manualAddress}
          address: ${envelope.address.toString()}
        respondId: ${envelope.respondId.toString()}
    returnAddress: ${envelope.returnAddress.toString()}
             body: ${decodeUtf8(envelope.body)}""";
}

WebSocketsClient startMessaging(String endpoint, Function onOpen) {
  return new WebSocketsClient(endpoint,
      onMessage: (Message message) {
        if (message.needsResponse) {
          lastReceivedMessage = message;
        }
        writeLog("incoming", "received message:\n${toString(message.envelope)}\n");
      },
      onOpen: (ConnectionMetadata metadata) {
        writeLog("info", "opened connection with directAddress: ${metadata.directAddress}, replyAddress: ${metadata.replyAddress}, isTrusted: ${metadata.isTrusted}");
        onOpen();
      },
      onClose: (WebSocketsClient client) {
        writeLog("info", "closed connection");
      },
      onError: (Event e) {
        writeLog("info", "unknown error");
      });
}

String getText(String selector) {
  var elem = querySelector(selector);
  if (elem == null) {
    return "";
  }
  if (elem is TextInputElementBase) {
    TextInputElementBase textElem = elem;
    var value = textElem.value;
    if (value != null) {
      return value;
    }
    return "";
  } else {
    var value = elem.text;
    if (value != null) {
      return value;
    }
    return "";
  }
}

int getInt(String selector, int def) {
  var text = getText(selector);
  try {
    return int.parse(text);
  } catch (e) {
    return def;
  }
}

bool isChecked(String selector) {
  var elem = querySelector(selector);
  if (elem == null) {
    return false;
  }
  if (elem is CheckboxInputElement) {
    CheckboxInputElement input = elem;
    return input.checked;
  }
  return false;
}

void setupConnectButton() {
  var connectButton = querySelector("#connect-button");
  connectButton.onClick.listen((event) {
    if (client == null && !connecting) {
      var endpoint = getText("#connect-endpoint");
      if (endpoint.isNotEmpty) {
        connecting = true;
        client = startMessaging(endpoint, () {
          connectButton.text = "disconnect";
          connecting = false;
        });
      }
    } else if (!connecting) {
      client.close();
      client = null;
      connectButton.text = "connect";
    }
  });
}

void setupSendButton() {
  var sendButton = querySelector("#send-button");
  sendButton.onClick.listen((event) {
    var localClient = client;
    if (localClient != null && !connecting) {
      var address = getText("#send-address");
      var body = new Uint8ClampedList.fromList(encodeUtf8(getText("#send-body")));
      var timeout = new Duration(milliseconds: getInt("#send-timeout", 30000));
      var needsResponse = isChecked("#send-needs-response");
      if (needsResponse) {
        var outgoingMessage = localClient.createMessage(body, address: address, respondTimeout: timeout, onResponse: (Message message) {
          if (message.needsResponse) {
            lastReceivedMessage = message;
          }
          writeLog("incoming", "received response:\n${toString(message.envelope)}\n");
        }, onRespondFail: (Message message) {
          writeLog("info", "did not receive a response to message:\n${toString(message.envelope)}\n");
        });
        outgoingMessage.send();
        writeLog("outgoing", "sent message:\n${toString(outgoingMessage.envelope)}\n");
      } else {
        var outgoingMessage = localClient.createMessage(body, address: address);
        outgoingMessage.send();
        writeLog("outgoing", "sent message:\n${toString(outgoingMessage.envelope)}\n");
      }
    } else {
      writeLog("info", "could not send message because there is no open connection");
    }
  });
}

void setupReplyButton() {
  var sendButton = querySelector("#reply-button");
  sendButton.onClick.listen((event) {
    var localClient = client;
    if (localClient != null && !connecting) {
      var replyMessage = lastReceivedMessage;
      if (replyMessage != null) {
        var body = new Uint8ClampedList.fromList(encodeUtf8(getText("#send-body")));
        var timeout = new Duration(milliseconds: getInt("#send-timeout", 30000));
        var needsResponse = isChecked("#send-needs-response");
        if (needsResponse) {
          var outgoingMessage = replyMessage.createResponse(body, respondTimeout: timeout, onResponse: (Message message) {
            if (message.needsResponse) {
              lastReceivedMessage = message;
            }
            writeLog("incoming", "received response:\n${toString(message.envelope)}\n");
          }, onRespondFail: (Message message) {
            writeLog("info", "did not receive a response to message:\n${toString(message.envelope)}\n");
          });
          outgoingMessage.send();
          writeLog("outgoing", "sent response:\n${toString(outgoingMessage.envelope)}\n");
        } else {
          var outgoingMessage = replyMessage.createResponse(body);
          outgoingMessage.send();
          writeLog("outgoing", "sent response:\n${toString(outgoingMessage.envelope)}\n");
        }
      } else {
        writeLog("info", "could not send reply because there is nothing to reply to");
      }
    } else {
      writeLog("info", "could not send reply because there is no open connection");
    }
  });
}

void main() {
  setupConnectButton();
  setupSendButton();
  setupReplyButton();
}