export const windowMs = 60000;

export function addSample(previous, cores, time) {
  const next = {};
  for (const { pid, stats } of cores) {
    if (!stats) continue;
    const { uploadTotal: up, downloadTotal: down } = stats;
    const last = previous[pid];
    let points = [];
    if (last && time > last.time && time - last.time < windowMs && up >= last.up && down >= last.down) {
      const seconds = (time - last.time) / 1000;
      points = last.points.filter(p => time - p.time < windowMs);
      points.push({ time, up: (up - last.up) / seconds, down: (down - last.down) / seconds });
    }
    next[pid] = { time, up, down, points };
  }
  return next;
}

export function bytes(value) {
  if (value == null) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  const i = Math.min(units.length - 1, Math.floor(Math.log2(Math.max(1, value)) / 10));
  return (value / 1024 ** i).toFixed(i === 0 ? 0 : 1) + " " + units[i];
}
