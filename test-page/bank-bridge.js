/**
 * Bank Bridge client.
 *
 * Drop-in reference for consuming the plugin from a web page. No dependencies.
 *
 *   const bridge = new BankBridge();
 *   bridge.onStatus = (s) => console.log(s);
 *   bridge.onBankUpdated = () => bridge.getBank().then(render);
 *   bridge.connect();
 *
 * The plugin binds 127.0.0.1 on the first free port in 37767-37776, so we scan the range.
 * ws://localhost is allowed from an https:// page because loopback counts as a trustworthy
 * origin — the same mechanism the OSRS Wiki DPS calculator uses in production.
 */
class BankBridge {
  static PORT_MIN = 37767;
  static PORT_MAX = 37776;

  constructor() {
    this.ws = null;
    this.hello = null;
    this.seq = 1;
    this.pending = new Map();
    this.onStatus = () => {};
    this.onBankUpdated = () => {};
    this._closedByUs = false;
  }

  async connect() {
    this._closedByUs = false;
    for (let port = BankBridge.PORT_MIN; port <= BankBridge.PORT_MAX; port++) {
      this.onStatus({ state: 'connecting', port });
      try {
        const ws = await this._tryPort(port);
        this._attach(ws, port);
        return this.hello;
      } catch (e) {
        // Port not listening, or listening but rejecting us. Try the next.
      }
    }
    this.onStatus({ state: 'not-found' });
    throw new Error('Bank Bridge is not running. Is RuneLite open with the plugin enabled?');
  }

  disconnect() {
    this._closedByUs = true;
    if (this.ws) this.ws.close();
    this.ws = null;
  }

  /** @returns {Promise<object>} the PlayerData payload */
  getBank() {
    return this._request('GetBank');
  }

  _tryPort(port) {
    return new Promise((resolve, reject) => {
      let ws;
      try {
        ws = new WebSocket(`ws://127.0.0.1:${port}`);
      } catch (e) {
        reject(e);
        return;
      }

      // A port with something else on it can open and then sit silent, so require the Hello
      // before treating the connection as ours.
      const timer = setTimeout(() => {
        try { ws.close(); } catch (e) {}
        reject(new Error('timeout'));
      }, 1500);

      ws.onerror = () => {
        clearTimeout(timer);
        reject(new Error('error'));
      };

      ws.onclose = () => {
        clearTimeout(timer);
        reject(new Error('closed'));
      };

      ws.onmessage = (ev) => {
        let msg;
        try {
          msg = JSON.parse(ev.data);
        } catch (e) {
          return;
        }
        if (msg._wsType !== 'Hello') return;
        clearTimeout(timer);
        this.hello = msg;
        resolve(ws);
      };
    });
  }

  _attach(ws, port) {
    this.ws = ws;
    this.port = port;

    ws.onmessage = (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch (e) {
        return;
      }

      if (msg._wsType === 'BankUpdated') {
        this.onBankUpdated(msg);
        return;
      }

      const waiter = this.pending.get(msg.sequenceId);
      if (!waiter) return;
      this.pending.delete(msg.sequenceId);
      if (msg._wsType === 'Error') waiter.reject(new Error(msg.error));
      else waiter.resolve(msg.payload);
    };

    ws.onclose = () => {
      this.ws = null;
      for (const w of this.pending.values()) w.reject(new Error('disconnected'));
      this.pending.clear();
      this.onStatus({ state: this._closedByUs ? 'disconnected' : 'lost' });
    };

    ws.onerror = () => {};

    this.onStatus({ state: 'connected', port, hello: this.hello });
  }

  _request(type) {
    return new Promise((resolve, reject) => {
      if (!this.ws) {
        reject(new Error('not connected'));
        return;
      }
      const sequenceId = this.seq++;
      this.pending.set(sequenceId, { resolve, reject });
      this.ws.send(JSON.stringify({ _wsType: type, sequenceId }));
      setTimeout(() => {
        if (this.pending.has(sequenceId)) {
          this.pending.delete(sequenceId);
          reject(new Error('request timed out'));
        }
      }, 5000);
    });
  }
}
