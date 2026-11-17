package com.mangler.reader

/**
 * Script injetado na página do navegador para achar as imagens do capítulo/galeria.
 * Rola a página (para disparar o lazy load) e coleta as imagens em níveis:
 *  1) páginas grandes (comportamento original, nada mudou aqui);
 *  2) se nada for achado: links que apontam direto para arquivos de imagem (miniatura -> imagem grande);
 *  3) imagens menores (GIFs/WebP animados em grade, miniaturas), usando o maior arquivo do srcset;
 *  4) imagens desenhadas como fundo (background-image).
 * Não usa o caractere "dólar" porque este texto passa por um template do Kotlin.
 */
object ExtractScript {
    const val TOKEN = "__TOKEN__"

    val JS = """
        (function(){
          var TOKEN='__TOKEN__';
          var EXT=['.jpg','.jpeg','.png','.webp','.gif','.avif','.bmp'];
          var BAD=/logo|icon|avatar|sprite|favicon|emoji|spinner|loading|button|banner/i;
          function abs(u){try{return new URL(u,location.href).href}catch(e){return ''}}
          function isImgUrl(u){
            var p=(u||'').split('#')[0].split('?')[0].toLowerCase();
            for(var i=0;i<EXT.length;i++){var e=EXT[i];if(p.length>=e.length&&p.lastIndexOf(e)===p.length-e.length)return true;}
            return false;
          }
          function okUrl(u){return u&&u.indexOf('data:')!==0&&u.indexOf('blob:')!==0;}
          function fromSrcset(s){
            if(!s) return '';
            var best='',bw=-1,parts=s.split(',');
            for(var i=0;i<parts.length;i++){
              var t=parts[i].trim().split(/\s+/);
              if(!t[0]) continue;
              var w=parseFloat(t[1])||1;
              if(w>=bw){bw=w;best=t[0];}
            }
            return best;
          }
          function pick(im){
            var c=[im.currentSrc,im.src,im.getAttribute('data-src'),im.getAttribute('data-lazy-src'),im.getAttribute('data-original'),im.getAttribute('data-url')];
            for(var i=0;i<c.length;i++){
              var u=c[i];
              if(okUrl(u)){
                var a=abs(u);
                if(a.indexOf('http')===0) return a;
              }
            }
            return '';
          }
          function pickBig(im){
            var c=[fromSrcset(im.getAttribute('srcset')),fromSrcset(im.getAttribute('data-srcset'))];
            var pic=im.closest?im.closest('picture'):null;
            if(pic){var ss=pic.querySelectorAll('source');for(var k=0;k<ss.length;k++){c.push(fromSrcset(ss[k].getAttribute('srcset')));}}
            for(var i=0;i<c.length;i++){
              if(okUrl(c[i])){var a=abs(c[i]);if(a.indexOf('http')===0) return a;}
            }
            return pick(im);
          }
          // nível 1: o comportamento original
          function collect(){
            var imgs=document.images, items=[], maxW=0;
            for(var i=0;i<imgs.length;i++){
              var im=imgs[i], u=pick(im);
              if(!u) continue;
              var r=im.getBoundingClientRect();
              var w=r.width||im.naturalWidth, h=r.height||im.naturalHeight;
              if(w<200||h<200) continue;
              items.push({u:u,w:w});
              if(w>maxW) maxW=w;
            }
            var seen={}, out=[];
            for(var j=0;j<items.length;j++){
              if(items[j].w>=maxW*0.6 && !seen[items[j].u]){seen[items[j].u]=1;out.push(items[j].u);}
            }
            return out;
          }
          function dedupe(list){
            var seen={}, out=[];
            for(var i=0;i<list.length;i++){if(list[i]&&!seen[list[i]]){seen[list[i]]=1;out.push(list[i]);}}
            return out;
          }
          function dim(im){
            var r=im.getBoundingClientRect();
            return {w:Math.max(r.width,im.naturalWidth||0),h:Math.max(r.height,im.naturalHeight||0)};
          }
          // nível 2: links que apontam direto para o arquivo da imagem
          function collectLinks(){
            var out=[], imgs=document.images;
            for(var i=0;i<imgs.length;i++){
              var im=imgs[i], a=im.closest?im.closest('a'):null;
              if(!a) continue;
              var h=abs(a.getAttribute('href')||'');
              if(h.indexOf('http')===0&&isImgUrl(h)&&!BAD.test(h)){
                var d=dim(im);
                if(d.w>=60&&d.h>=60) out.push(h);
              }
            }
            return dedupe(out);
          }
          // nível 3: qualquer imagem razoável (GIF/WebP animado pequeno, miniaturas)
          function collectSmall(){
            var out=[], imgs=document.images;
            for(var i=0;i<imgs.length;i++){
              var im=imgs[i], u=pickBig(im);
              if(!u||BAD.test(u)) continue;
              var d=dim(im);
              if(d.w<60||d.h<60) continue;
              out.push(u);
            }
            return dedupe(out);
          }
          // nível 4: imagens de fundo
          function collectBackgrounds(){
            var out=[], els=document.querySelectorAll('div,span,a,li,figure,section');
            for(var i=0;i<els.length;i++){
              var el=els[i], bg='';
              try{bg=getComputedStyle(el).backgroundImage||'';}catch(e){}
              if(!bg||bg==='none') continue;
              var m=/url\(["']?([^"')]+)["']?\)/.exec(bg);
              if(!m) continue;
              var r=el.getBoundingClientRect();
              if(r.width<60||r.height<60) continue;
              var u=abs(m[1]);
              if(u.indexOf('http')===0&&okUrl(u)&&!BAD.test(u)) out.push(u);
            }
            return dedupe(out);
          }
          function collectAll(){
            var a=collect(); if(a.length) return a;
            a=collectLinks(); if(a.length) return a;
            a=collectSmall(); if(a.length) return a;
            return collectBackgrounds();
          }
          var steps=0, y=window.scrollY||0;
          var timer=setInterval(function(){
            steps++;
            y+=Math.max(window.innerHeight*0.9,300);
            window.scrollTo(0,y);
            var H=Math.max(document.documentElement.scrollHeight, document.body?document.body.scrollHeight:0);
            if(y>=H+200||steps>=60){
              clearInterval(timer);
              setTimeout(function(){
                window.scrollTo(0,0);
                ManglerBridge.onImages(TOKEN, JSON.stringify(collectAll()));
              },800);
            }
          },250);
        })();
    """.trimIndent()
}
