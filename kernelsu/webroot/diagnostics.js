"use strict";

const byId = id => document.getElementById(id);
const tcpStates = {
  "01": "已建立", "02": "SYN-SENT", "03": "SYN-RECV", "04": "FIN-WAIT-1",
  "05": "FIN-WAIT-2", "06": "TIME-WAIT", "07": "CLOSED", "08": "CLOSE-WAIT",
  "09": "LAST-ACK", "0A": "LISTEN", "0B": "CLOSING",
};

function showSockets(rows, errors, empty) {
  const container = byId("sockets");
  container.replaceChildren();
  if (rows.length) {
    const table = document.createElement("table");
    const head = table.createTHead().insertRow();
    for (const title of ["PID", "UID", "Inode", "TCP 状态"]) {
      const th = document.createElement("th");
      th.textContent = title;
      head.append(th);
    }
    const body = table.createTBody();
    for (const row of rows) {
      const [pid, uid, inode, state] = row.split("\t");
      const tr = body.insertRow();
      for (const value of [pid, uid, inode, tcpStates[state] || state]) tr.insertCell().textContent = value;
    }
    container.append(table);
  }
  for (const text of errors) {
    const message = document.createElement("p");
    message.textContent = text;
    container.append(message);
  }
  if (!rows.length && !errors.length) container.textContent = empty;
}

function render(stdout) {
  const records = {process: [], route4: [], route6: [], socket: [], socketError: [], timeout: [], logError: []};
  const lines = stdout.trimEnd().split(/\r?\n/);
  if (lines.pop() !== "done") throw new Error("采样未完成");
  for (const line of lines) {
    const tab = line.indexOf("\t");
    const key = line.slice(0, tab);
    if (tab > 0 && Object.prototype.hasOwnProperty.call(records, key)) records[key].push(line.slice(tab + 1));
  }
  const running = records.process.length > 0;
  byId("processes").textContent = records.process.map(row => {
    const [pid, uid] = row.split("\t");
    return "PID " + pid + " · UID " + uid;
  }).join(" / ") || "未运行";
  for (const family of ["route4", "route6"]) {
    byId(family).textContent = records[family].join("\n") || "未发现模块规则或路由";
  }
  showSockets(records.socket, records.socketError, running ? "采样时没有 TCP/853 套接字" : "内核未运行");
  byId("timeouts").textContent = [...records.timeout, ...records.logError].join("\n") ||
    (running ? "当前日志中未发现 DNS 超时" : "内核未运行");
}

let sequence = 0;
function readSnapshot() {
  return new Promise((resolve, reject) => {
    const callback = "clashSnapshot" + (++sequence);
    const timer = setTimeout(() => { delete window[callback]; reject(new Error("读取超时")); }, 15000);
    window[callback] = (code, stdout, stderr) => {
      clearTimeout(timer);
      delete window[callback];
      if (code === 0) resolve(stdout);
      else reject(new Error(stderr.trim() || "诊断脚本执行失败"));
    };
    try {
      window.ksu.exec("sh /data/adb/modules/clash_android_tproxy/diagnostics.sh", "{}", callback);
    } catch (error) {
      clearTimeout(timer);
      delete window[callback];
      reject(error);
    }
  });
}

async function refresh() {
  const button = byId("refresh");
  if (button.disabled) return;
  button.disabled = true;
  byId("updated").textContent = "正在读取设备数据…";
  try {
    render(await readSnapshot());
    byId("updated").textContent = "采样于 " + new Date().toLocaleTimeString();
  } catch (error) {
    for (const id of ["processes", "route4", "route6", "sockets", "timeouts"]) byId(id).textContent = "读取失败";
    byId("updated").textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

byId("refresh").addEventListener("click", refresh);
refresh();
