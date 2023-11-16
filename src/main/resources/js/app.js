document.addEventListener('DOMContentLoaded', (event) => {
  const editor = new Quill('#editor', {
    theme: 'snow'
  });

  const location = window.location;
//  const ws = new WebSocket('ws://localhost:8080/api/ws');
  const wsUrl = `ws://${location.host}${location.pathname}/ws`
  console.log(wsUrl)
  console.log("opening websocket on ", wsUrl)
  const ws = new WebSocket(wsUrl);

  let userName = null;

  ws.onopen = () => {
    console.log('WebSocket connection established on URL $wsUrl');
    pingInterval = setInterval(() => {
      const pingMessage = JSON.stringify({ type: "Ping", user: userName });
//      console.log("Sending ping:", pingMessage);
      ws.send(pingMessage);
    }, 3000);
  };

    ws.onmessage = (event) => {
      console.log('Event received: ', event);
      const message = JSON.parse(event.data);
      console.log('Msg received: ', message);
      console.log('Msg type: ', message.type);
      switch (message.type) {
        case "delta":
            editor.updateContents(message.delta);
            break;
        case "Welcome":
            console.log("Welcome, user ", message.user)
            userName = message.user;
            break;
        default:
            console.log('system message', message);
      }
    };



  ws.onclose = () => {
    console.log('WebSocket connection closed');
    clearInterval(pingInterval);
  };

  editor.on('text-change', (delta, oldDelta, source) => {
    console.log("editor delta: ", delta)
    if (source === 'user') {
      let wrapperObject = {
        type: "delta",
        delta: delta
      }
      console.log('Sending update:', wrapperObject);
      ws.send(JSON.stringify(wrapperObject));
    }
  });
});
