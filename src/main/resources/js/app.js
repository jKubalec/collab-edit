document.addEventListener('DOMContentLoaded', (event) => {
  var Delta = Quill.import('delta');

  const editor = new Quill('#editor', {
    theme: 'snow'
  });

  const location = window.location;
  const wsUrl = `ws://${location.host}${location.pathname}/ws`
  console.log(wsUrl)
  console.log("opening websocket on ", wsUrl)
  const ws = new WebSocket(wsUrl);

  let userName = null;
  let isProgrammaticChange = false;

  function bindTextChangeEvent() {
//              weird JS hack
        setTimeout(() => {
            console.log("follow text changes - on");
            editor.on('text-change', onTextChange);
            isProgrammaticChange = false;
        }, 0);
  }

  function unbindTextChangeEvent() {
      console.log("follow text changes - off");
      editor.off('text-change', onTextChange);
  }

  ws.onopen = () => {
    console.log("WebSocket connection established on URL " + wsUrl);
    ws.send(JSON.stringify({type: "ContentRequest"}))
    pingInterval = setInterval(() => {
      const pingMessage = JSON.stringify({ type: "Ping" });
      ws.send(pingMessage);
    }, 3000);
  };

    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      switch (message.type) {
        case "delta":
            isProgrammaticChange = true;
            unbindTextChangeEvent();

            editor.updateContents(message.delta, "api");
            console.log('Recieved delta: ', JSON.stringify(message.delta))

            setTimeout(() => {
                if (isProgrammaticChange) {
                  console.log("text-change - on")
                  bindTextChangeEvent();
                }
                isProgrammaticChange = false;
            }, 0);
            break;
        case "Welcome":
            console.log("Welcome, user ", message.user);
            userName = message.user;
            console.log("usertName ", userName);
            document.getElementById("header").innerHTML = "Hello, " + userName.name + "!";
            break;
        case "ContentRequest":
            let wrapperObject = {
              type: "EditorContent",
              deltas: [],
              content: editor.root.innerHTML
            }
            console.log('Requested content - sending editor content back:', wrapperObject);
            ws.send(JSON.stringify(wrapperObject));
            break;
        case "EditorContent":
            console.log("Updated content recvd ", message);
            setNewContent(editor, message)
            break;
        default:
            console.log('system message', message);
      }
    };

  function setNewContent(editor, serverMessage) {
      isProgrammaticChange = true;
      unbindTextChangeEvent();

      let newContents = serverMessage.content;
      let newDeltas = serverMessage.deltas

      console.log("New content", JSON.stringify(newContents));
      console.log("New deltas", JSON.stringify(newDeltas));

      editor.root.innerHTML = newContents;
      editor.updateContents(newDeltas, "api")

      bindTextChangeEvent();
      console.log("should monitor text changes now.")
  }

  ws.onclose = () => {
    unbindTextChangeEvent();
    console.log('WebSocket connection closed');
    document.getElementById("header").innerHTML = 'WebSocket connection closed';
    editor.enable(false)
    clearInterval(pingInterval);
  };

  function onTextChange(delta, oldDelta, source) {
    console.log("Got delta " + JSON.stringify(delta) + " isProgrammaticChange = " + isProgrammaticChange)
    if (!isProgrammaticChange){
      console.log("editor delta: ", delta)
          if (source === 'user') {
            let wrapperObject = {
              type: "delta",
              delta: delta
            }
            console.log('Sending update:', wrapperObject);
            ws.send(JSON.stringify(wrapperObject));
          }
    }
  }

  bindTextChangeEvent();

    document.getElementById('clearContent').addEventListener('click', function() {
        if (ws && ws.readyState === WebSocket.OPEN) {
            let message = JSON.stringify({ type: "ClearContent" })
            ws.send(message);
            console.log('Message sent:', message);
        } else {
            console.log('WebSocket is not connected.');
        }
    });
});
