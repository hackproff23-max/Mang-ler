# Mang-ler

Leitor de mangá para Android: tela cheia, zoom (pinça e toque duplo), efeito de folha virando e leitura no modo mangá (direita → esquerda) ou livro.

Abre arquivos locais **CBZ / ZIP** e **pastas de imagens**. O APK assinado é gerado pelo GitHub Actions (aba Actions → Artifacts → Mang-ler-apk).

## Navegador (novo)

O botão **Navegar e ler online** abre um mini-navegador (WebView) com busca pelo **Google**, barra de endereço, voltar/avançar e favoritos.
Ao abrir um capítulo, aparece o aviso **"Abrir em tela cheia com virar página?"**. Em "Abrir", as páginas do capítulo vão para o leitor (tela cheia, efeito de folha virando, zoom). O botão 📖 da barra faz o mesmo em qualquer página, e em ⋮ dá para desligar o aviso.

### Galerias com miniaturas e paginação numérica

No botão 📖 o app primeiro tenta reconhecer uma galeria (miniaturas + paginação 1, 2, 3...). Se for, ele acha todas as páginas numeradas, lê as miniaturas de cada uma em segundo plano (com os cookies e o User-Agent da WebView), abre cada página individual para achar a imagem grande e entrega tudo ao leitor numa única sequência, na ordem original. Um diálogo mostra páginas identificadas, imagens encontradas/resolvidas e o progresso, com botão Cancelar. Se a página não for uma galeria, vale o extrator de capítulo de antes.
