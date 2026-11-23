<script>
  let { snapshot, placeholder } = $props();
  const tcpStates = {
    "01": "已建立", "02": "SYN-SENT", "03": "SYN-RECV", "04": "FIN-WAIT-1",
    "05": "FIN-WAIT-2", "06": "TIME-WAIT", "07": "CLOSED", "08": "CLOSE-WAIT",
    "09": "LAST-ACK", "0A": "LISTEN", "0B": "CLOSING",
  };
</script>

<section class="core">
  <h2>原生内核</h2>
  <p class="metric">{snapshot ? snapshot.processes.map(p => `PID ${p.pid} · UID ${p.uid}`).join(" / ") || "未运行" : placeholder}</p>
</section>
<section>
  <h2>策略路由</h2>
  <p class="hint">优先级 9055 · 路由表 17893</p>
  {#each [4, 6] as family}
    <h3>IPv{family}</h3>
    <pre>{snapshot ? snapshot["route" + family].join("\n") || "未发现模块规则或路由" : placeholder}</pre>
  {/each}
</section>
<section>
  <h2>DoT 套接字</h2>
  <p class="hint">内核持有的出站 TCP/853 连接</p>
  <div id="sockets">
    {#if !snapshot}{placeholder}
    {:else if !snapshot.processes.length}内核未运行
    {:else}
      {#if snapshot.processes.some(p => p.sockets.length)}
        <table>
          <thead><tr><th>PID</th><th>UID</th><th>Inode</th><th>TCP 状态</th></tr></thead>
          <tbody>
            {#each snapshot.processes as core}
              {#each core.sockets as socket}
                <tr><td>{core.pid}</td><td>{socket.uid}</td><td>{socket.inode}</td><td>{tcpStates[socket.state] ?? socket.state}</td></tr>
              {/each}
            {/each}
          </tbody>
        </table>
      {:else if !snapshot.processes.some(p => p.socket_error)}采样时没有 TCP/853 套接字{/if}
      {#each snapshot.processes as core}
        {#if core.socket_error}<p>无法读取 PID {core.pid} 的套接字：{core.socket_error}</p>{/if}
      {/each}
    {/if}
  </div>
</section>
<section>
  <h2>最近的 DNS 超时</h2>
  <p class="hint">当前内核进程的系统日志，每个进程最近 20 条</p>
  <div id="timeouts">
    {#if !snapshot}{placeholder}
    {:else if !snapshot.processes.length}内核未运行
    {:else}
      {#each snapshot.processes as core}
        {#if core.log_error}<p>无法读取 PID {core.pid} 的日志：{core.log_error}</p>{/if}
        {#each core.timeouts as line}<pre>{core.pid}　{line}</pre>{/each}
      {/each}
      {#if !snapshot.processes.some(p => p.log_error || p.timeouts.length)}当前日志中未发现 DNS 超时{/if}
    {/if}
  </div>
</section>
