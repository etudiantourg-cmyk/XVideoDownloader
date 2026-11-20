const CACHE="xvd-web-v3";
self.addEventListener("install",e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(["./","./manifest.webmanifest","./icon.svg"]))));
self.addEventListener("activate",e=>e.waitUntil((async()=>{for(const key of await caches.keys())if(key!==CACHE)await caches.delete(key);await self.clients.claim()})()));
self.addEventListener("fetch",e=>{if(e.request.method==="GET"&&new URL(e.request.url).origin===location.origin)e.respondWith(fetch(e.request).then(r=>{const copy=r.clone();caches.open(CACHE).then(c=>c.put(e.request,copy));return r}).catch(()=>caches.match(e.request)))});
