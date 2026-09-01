import type { Config } from "@netlify/functions";

const SOURCE = "https://raw.githubusercontent.com/ddotroo-cloud/siberia-cumple/main/regalos.html";

export default async () => {
  const res = await fetch(SOURCE, { headers: { "User-Agent": "siberia-cumple" } });
  if (!res.ok) return new Response("No se pudo cargar la mesa de regalos", { status: 502 });
  let html = await res.text();

  // Navegación solicitada: volver a la invitación y sin botón independiente de Sucursales.
  html = html.replace('<a href="index.html">Inicio</a>', '<a href="index.html">← Regresar a la invitación</a>');
  html = html.replace('<a href="#regalos">Sucursales</a>', '');

  // Quitar la descripción del husky en la sección inferior.
  html = html.replace('<small>Husky hembra café<br>de ojos azules</small>', '');

  // Hacer todas las direcciones clicables y abrir Google Maps.
  html = html.replace(
    "document.getElementById('addresses').innerHTML=s.addresses.map(a=>`<div class=\"address\"><b>${a[0]}</b>${a[1]}</div>`).join('');",
    "document.getElementById('addresses').innerHTML=s.addresses.map(a=>{const q=encodeURIComponent(a[1]+' Mérida Yucatán');return `<a class=\"address map-link\" href=\"https://www.google.com/maps/search/?api=1&query=${q}\" target=\"_blank\" rel=\"noopener\"><b>${a[0]}</b>${a[1]}<span class=\"map-hint\">📍 Abrir en Google Maps</span></a>`}).join('');"
  );

  // Mantener la paleta de la invitación original (oscuro, dorado, rosa y crema).
  html = html.replace('</style>', `
    .map-link{display:block;text-decoration:none;color:inherit;transition:.18s ease}
    .map-link:hover,.map-link:focus-visible{border-color:#d5a84c;box-shadow:0 8px 22px #0002;transform:translateY(-1px);outline:none}
    .map-hint{display:block;margin-top:7px;color:#b7862d;font-weight:800;font-size:12px}
    :root{--pink:#db7785;--pink2:#e9a1aa;--ink:#2b211d;--cream:#f8efe3;--line:#d7b982;--soft:#f8efe3}
    body{background:linear-gradient(180deg,#211b19 0,#31251f 220px,#f6eee4 590px);}
    .site-head{background:rgba(31,25,23,.96);border-bottom-color:#6e5536}
    .brand-copy small,.links a{color:#f8efe3}.links a.active{color:#e4b85e;border-color:#e4b85e}
    .gift-jump,.ribbon,.product-link{background:linear-gradient(135deg,#c99336,#e1b45d)!important}
    .hero h1{color:#f7e8d3}.hero p{color:#f5d8dc}.deco{color:#e0b45e}
    .tip{color:#f1dfcf}.tip span{color:#ef9da8}
    .gift-card{background:#fffaf4;border-color:#ddc89f}.gift-card:hover{border-color:#d2a34a}
    .like,.wellbeing,.modal{background:#fff8ef}.like-content h3{color:#ba842d}.footer{background:linear-gradient(90deg,#2c2220,#171311);color:#e7bd68}
    .dogmark{background:#2b211f;border-color:#d6a34b;color:#f0c36f}
  </style>`);

  return new Response(html, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-cache, no-store, must-revalidate",
    },
  });
};

export const config: Config = { path: "/mesa-render" };
