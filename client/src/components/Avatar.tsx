/** A coloured initial. The hue comes from the name, so a person keeps their colour everywhere. */
export function Avatar({ name, size = 40 }: { name: string; size?: number }) {
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  const hue = Math.abs(hash) % 360;
  return (
    <span
      className="avatar"
      aria-hidden="true"
      style={{ width: size, height: size, fontSize: size * 0.42, background: `hsl(${hue} 45% 52%)` }}
    >
      {name.charAt(0).toUpperCase()}
    </span>
  );
}
