var logOutput = $("#log");
var endpointInput = $("#endpoint");
var connectButton = $("#connect");
var disconnectButton = $("#disconnect");
var sendButton = $("#send");
var messageInput = $("#message");

var socket = undefined;

function log(message) {
  logOutput.val(logOutput.val() + message + "\n")
}

function handlePing(message) {
  if (message == 'ping') {
    socket.send('pong');
    log("received ping");
    return true;
  } else {
    return false;
  }
}

function handlePong(message) {
  return message == 'pong';
}

connectButton.click(function(e) {
  e.preventDefault();
  var endpointUrl = endpointInput.val();
  socket = new WebSockHop(endpointUrl);
  socket.formatter = new WebSockHop.StringFormatter();
  socket.defaultRequestTimeoutMsecs = 5000;
  socket.defaultDisconnectOnRequestTimeout = true;
  socket.pingIntervalMsecs = 15000;
  socket.pingResponseTimeoutMsecs = 10000;
  socket.formatter.pingMessage = 'ping';
  socket.formatter.handlePing = handlePing;
  socket.formatter.handlePong = handlePong;

  socket.on('opened', function() {
    log("Opened connection to: " + endpointUrl);
    connectButton.addClass("hidden");
    disconnectButton.removeClass("hidden");
  });

  socket.on('message', function(message) {
    log("Received:\n" + message);
  });

  socket.on('closed', function() {
    log("Closed connection");
    connectButton.removeClass("hidden");
    disconnectButton.addClass("hidden");
    socket = undefined;
  });

  socket.on()
});

disconnectButton.click(function(e) {
  e.preventDefault();
  socket.close();
});

sendButton.click(function(e) {
  e.preventDefault();
  log("Sent:\n" + messageInput.val());
  socket.send(messageInput.val());
});
