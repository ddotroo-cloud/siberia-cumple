import type { Config } from "@netlify/functions";

const GIFT_SOURCE = "https://raw.githubusercontent.com/ddotroo-cloud/siberia-cumple/main/regalos.html";
const INVITE_SOURCE = "https://raw.githubusercontent.com/ddotroo-cloud/siberia-cumple/main/index.html";

export default async () => {
  const headers = { "User-Agent": "siberia-cumple" };
  const [giftRes, inviteRes] = await Promise.all([
    fetch(GIFT_SOURCE, { headers }),
    fetch(INVITE_SOURCE, { headers }),
  ]);
  if (!giftRes.ok) return new Response("No se pudo cargar la mesa de regalos", { status: 502 });

  let html = await giftRes.text();
  const invite = inviteRes.ok ? await inviteRes.text() : "";
  const dogSrc =
    invite.match(/<img[^>]*class=["'][^"']*siberia-source[^"']*["'][^>]*src=["']([^"']+)["']/i)?.[1] ||
    invite.match(/<img[^>]*class=["'][^"']*car-art[^"']*["'][^>]*src=["']([^"']+)["']/i)?.[1] ||
    "";

  html = html.replace('<a href="index.html">Inicio</a>', '<a href="index.html">← Regresar a la invitación</a>');
  html = html.replace('<a href="#regalos">Sucursales</a>', '');
  html = html.replace('<small>Husky hembra café<br>de ojos azules</small>', '');

  html = html.replace(
    /<div class="rule">En Siberia nos enfocamos en productos naturales, seguros y de calidad[\s\S]*?<\/div>/i,
    ""
  );

  if (dogSrc) {
    html = html.replace('<div class="dogmark">🐺</div>', `<div class="dogmark"><img src="${dogSrc}" alt="Siberia"></div>`);
    html = html.replace('<div class="sibe-face">🐺</div>', `<div class="sibe-face"><img src="${dogSrc}" alt="Siberia"></div>`);
  }

  html = html.replace(
    "document.getElementById('addresses').innerHTML=s.addresses.map(a=>`<div class=\"address\"><b>${a[0]}</b>${a[1]}</div>`).join('');",
    "document.getElementById('addresses').innerHTML=s.addresses.map(a=>{const q=encodeURIComponent(a[1]+' Mérida Yucatán');return `<a class=\"address map-link\" href=\"https://www.google.com/maps/search/?api=1&query=${q}\" target=\"_blank\" rel=\"noopener\"><b>${a[0]}</b>${a[1]}<span class=\"map-hint\">📍 Abrir en Google Maps</span></a>`}).join('');"
  );

  html = html.replace('</style>', `
    .map-link{display:block;text-decoration:none;color:inherit;transition:.18s ease}
    .map-link:hover,.map-link:focus-visible{border-color:#ec0b67;box-shadow:0 8px 22px #ec0b6722;transform:translateY(-1px);outline:none}
    .map-hint{display:block;margin-top:7px;color:#ec0b67;font-weight:800;font-size:12px}
    :root{--pink:#ec0b67;--pink2:#f45b91;--ink:#171112;--cream:#fff7f3;--line:#f1b6c9;--soft:#fff1f5}
    body{background:linear-gradient(180deg,#fff7f3 0,#ffeef3 330px,#fffaf7 760px);color:#171112}
    .site-head{background:rgba(255,247,243,.96);border-bottom-color:#f2bed0}
    .brand-copy strong{color:#ec0b67}
    .brand-copy small,.links a{color:#171112}
    .links a.active{color:#ec0b67;border-color:#ec0b67}
    .gift-jump,.ribbon,.product-link{background:#ec0b67!important;color:#fff!important}
    .hero h1{color:#111}.hero p{color:#3d292b}.deco{color:#ec0b67}
    .tip{color:#745d61}.tip span{color:#ec0b67}
    .gift-card{background:#fff;border-color:#f1c2d0;box-shadow:0 5px 16px #5a23320d}
    .gift-card:hover{border-color:#ec0b67;box-shadow:0 12px 28px #ec0b6718}
    .like,.wellbeing,.modal{background:#fff8f5;border-color:#f1c2d0}
    .like-content h3{color:#ec0b67}
    .footer{background:#171112;color:#fff}
    .dogmark,.sibe-face{position:relative;overflow:hidden;background:#ffe4ed;border-color:#ec0b67;color:#ec0b67}
    .dogmark img,.sibe-face img{position:absolute;width:340%;height:auto;max-width:none;left:0;top:0;transform:translate(-68%,-22%);object-fit:unset;display:block}
    .rules{grid-template-columns:repeat(2,minmax(0,1fr))!important;max-width:780px;margin:0 auto}
    .rule:last-child{border-right:0;text-align:center}
    @media(max-width:700px){.rules{grid-template-columns:1fr!important}}
  </style>`);

  return new Response(html, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-cache, no-store, must-revalidate",
    },
  });
};

export const config: Config = { path: "/regalos.html" };
