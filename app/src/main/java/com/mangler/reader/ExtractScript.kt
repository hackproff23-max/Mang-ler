package com.mangler.reader

/**
 * Script injetado na página do navegador para achar as imagens do capítulo/galeria.
 * Rola a página (para disparar o lazy load) e coleta as imagens em níveis:
 *  1) páginas grandes (comportamento original, nada mudou aqui);
 *  2) se nada for achado: vídeos da página (WebM/MP4), marcados com "#mv" no fim do endereço;
 *  3) links que apontam direto para arquivos de imagem (miniatura -> imagem grande);
 *  4) imagens menores (GIFs/WebP animados em grade, miniaturas), usando o maior arquivo do srcset;
 *  5) imagens desenhadas como fundo (background-image).
 * Nos níveis 3 e 4 imagens em faixa (muito mais largas que altas, ou o contrário) são ignoradas:
 * são "sprites" com vários quadros lado a lado (prévia de vídeo), não a imagem de verdade.
 * Não usa o caractere "dólar" porque este texto passa por um template do Kotlin.
 */
object ExtractScript {
    const val TOKEN = "__TOKEN__"

    val JS = """
        (function(){
          var TOKEN='__TOKEN__';
          var EXT=['.jpg','.jpeg','.png','.webp','.gif','.avif','.bmp'];
          var VID=['.webm','.mp4','.m4v','.mov'];
          var BAD=/logo|icon|avatar|sprite|favicon|emoji|spinner|loading|button|banner/i;
          function abs(u){try{return new URL(u,location.href).href}catch(e){return ''}}
          function endsIn(u,list){
            var p=(u||'').split('#')[0].split('?')[0].toLowerCase();
            for(var i=0;i<list.length;i++){var e=list[i];if(p.length>=e.length&&p.lastIndexOf(e)===p.length-e.length)return true;}
            return false;
          }
          function isImgUrl(u){return endsIn(u,EXT);}
          function isVidUrl(u){return endsIn(u,VID);}
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
          // imagem em faixa (sprite com quadros lado a lado): não é a imagem de verdade
          function strip(im){
            var nw=im.naturalWidth||0, nh=im.naturalHeight||0;
            if(nw<1||nh<1) return false;
            return nw/nh>6||nh/nw>6;
          }
          // nível 2: vídeos da página (WebM/MP4). O "#mv" avisa o leitor que é vídeo, mesmo sem extensão.
          function vidOf(v){
            var c=[v.currentSrc,v.getAttribute('src'),v.getAttribute('data-src')];
            var ss=v.querySelectorAll('source');
            for(var k=0;k<ss.length;k++){c.push(ss[k].getAttribute('src'));c.push(ss[k].getAttribute('data-src'));}
            var best='';
            for(var i=0;i<c.length;i++){
              if(!okUrl(c[i])) continue;
              var a=abs(c[i]);
              if(a.indexOf('http')!==0) continue;
              if(/\.webm/i.test(a.split('?')[0])) return a;
              if(!best) best=a;
            }
            return best;
          }
          function collectVideos(){
            var out=[], vs=document.querySelectorAll('video');
            for(var i=0;i<vs.length;i++){
              var v=vs[i], r=v.getBoundingClientRect();
              var w=Math.max(r.width,v.videoWidth||0), h=Math.max(r.height,v.videoHeight||0);
              if(w<100||h<100) continue;
              var u=vidOf(v);
              if(u) out.push(u.split('#')[0]+'#mv');
            }
            return dedupe(out);
          }
          // nível 3: links que apontam direto para o arquivo da imagem
          function collectLinks(){
            var out=[], imgs=document.images;
            for(var i=0;i<imgs.length;i++){
              var im=imgs[i], a=im.closest?im.closest('a'):null;
              if(!a) continue;
              var h=abs(a.getAttribute('href')||'');
              if(h.indexOf('http')===0&&isImgUrl(h)&&!BAD.test(h)){
                var d=dim(im);
                if(d.w>=60&&d.h>=60&&!strip(im)) out.push(h);
              }
            }
            return dedupe(out);
          }
          // nível 4: qualquer imagem razoável (GIF/WebP animado pequeno, miniaturas)
          function collectSmall(){
            var out=[], imgs=document.images;
            for(var i=0;i<imgs.length;i++){
              var im=imgs[i], u=pickBig(im);
              if(!u||BAD.test(u)) continue;
              var d=dim(im);
              if(d.w<60||d.h<60) continue;
              if(strip(im)) continue;
              out.push(u);
            }
            return dedupe(out);
          }
          // nível 5: imagens de fundo
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
            a=collectVideos(); if(a.length) return a;
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
