# Pré-moldados personalizados (AIBuilder)

Esta pasta é onde você adiciona seus próprios pré-moldados. Cada pré-moldado é um arquivo `.json`
com a planta de uma construção. O construtor a monta blocos por blocos, sem gastar materiais.

## Arquivos desta pasta

- `LEIA-ME.md`: este guia.
- `PROMPT-EN.md`: prompt completo, em inglês, para pedir a uma IA (ChatGPT, Claude etc.) que gere o JSON.
- `exemplo_abrigo.json.example`: um exemplo pronto. A extensão `.example` faz com que ele **não** seja carregado.
  Para testá-lo, copie-o com o nome `abrigo_madeira.json` e recarregue.

## Como criar um pré-moldado

1. Abra `PROMPT-EN.md`, copie o conteúdo entre `=== BEGIN PROMPT ===` e `=== END PROMPT ===`,
   e troque a linha `REQUEST` pelo que você quer. Exemplo: *"Uma casa de dois andares, 9 x 7,
   com varanda, porta de carvalho e janelas de vidro."*
2. Envie para a IA. A resposta deve ser **um único objeto JSON**, sem texto em volta.
   Se ela vier com ```` ``` ```` ou com explicações, peça de novo: *"Responda só com o JSON, sem comentários."*
3. Salve a resposta como `nome_do_arquivo.json` nesta pasta. O campo `id` dentro do JSON é o nome
   que você usa no jogo, então ele deve ser único (não pode repetir um pré-moldado embutido, como `casa_campo`).
4. No jogo, como operador, rode `/construtor pre-moldado recarregar`. O servidor mostra quantos
   pré-moldados carregaram e os erros de cada arquivo que foi recusado.
5. Corrija os erros (é só editar o JSON ou pedir à IA) e recarregue de novo.

## Direção da entrada e limites

Use `/construtor <nome> orientacao norte|sul|leste|oeste` antes de escolher o modelo,
ou escolha a direção no menu de pré-moldados. A preferência é salva por construtor.
`orientacao original` mantém a direção original do modelo. O sol nasce a Leste e se põe a Oeste.

Para uma única construção: `/construtor <nome> pre-moldado <id> oeste`.
O JSON pode declarar `"entrance": "north"`, `"south"`, `"east"` ou `"west"` (padrão `south`).
Esse campo descreve a direção da entrada na geometria original. A rotação move todos os blocos,
portas, camas, escadas, eixos de troncos e conexões de vidraças. A prévia de tamanho e a confirmação
usam a planta já girada; Leste/Oeste podem trocar largura e profundidade. Obras já confirmadas
continuam com a orientação capturada.

Os limites vêm de `construction.*`: largura/profundidade/altura máximas de 200×200×200 e
volume 8000000 por padrão, configuráveis inclusive para valores maiores. `zone.build-height`
é apenas a altura automática inicial da seleção (100 por padrão), ajustável no menu até o
máximo configurado. Marque novamente áreas antigas após mudar a altura automática.
`max-project-blocks` tem padrão 2500; o operador pode aumentá-lo conforme a geometria e o servidor.
Também são configuráveis `max-parts` (4096), `max-openings` (1024), `max-processing-visits`
(128000000), `max-file-bytes` (67108864) e `preview-max-blocks` (20000). O limite de ações
individuais por resposta da IA é `construction.max-ai-actions` (250); plantas blueprint usam partes.
Após editar a configuração, `/construtor reload` relê os limites e modelos, interrompendo trabalhos.
Consulte `LIMITES-CONFIGURAVEIS.md`. A prévia pode mostrar uma amostra identificada, com total real.

## Onde fica a posição (x, y, z)

O JSON **não tem coordenadas do mundo**. Ele descreve a construção a partir do canto da área marcada.
Quem escolhe o local é você, com a ferramenta do construtor:

1. Pegue a **pá de diamante** do construtor e marque uma área no chão, onde a construção deve ficar
   (a área precisa ser pelo menos do tamanho da construção).
2. Rode `/construtor <nome> pre-moldado <id>`. O construtor mostra o plano.
3. Confira o plano e confirme com `/construtor sim` (ou o botão Confirmar no menu do construtor).

Trocar o local é só marcar outra área e repetir o comando.

## Como o construtor lê o JSON

- O canto mínimo da construção fica no canto mínimo da área marcada; o construtor centraliza a
  construção se ela sair um pouco da área.
- `y = 0` é a camada do chão. Se o chão estiver irregular, o construtor pode nivelar até 2 blocos e
  subir a base até 3 blocos (ele mostra isso no plano antes de começar).
- Cada `part` é aplicada na ordem: uma parte posterior sobrepõe a anterior (use isso para colocar
  uma porta ou janela dentro de uma parede).

## Regras que o servidor verifica

- Nome (`id`): 3 a 40 letras minúsculas, números ou `_`, começando por letra. Único.
- `title` e `description`: texto de 1 a 60 e 1 a 240 caracteres.
- Não pode ter campos de posição no mundo (`x`, `y`, `z`, `world`, `origin`, `area`, `location`, `pos`).
- Até 4096 partes e 2500 blocos por padrão; dimensões máximas 200×200×200. Todos estes valores são configuráveis pelo servidor.
- Qualquer material de bloco não vazio disponível na API Paper 26.3+ do servidor pode ser usado, exceto os materiais protegidos abaixo. Materiais precisam existir na versão do servidor que vai carregar o arquivo.
- O servidor valida o `BlockData` e as condições de colocação no local. Para blocos com estado, como portas, escadas, vidraças, folhas, luzes e trilhos, inclua `data` para definir orientação e conexões.
- Materiais protegidos: TNT, lava, fogo, bedrock, barreira, blocos de comando e spawners. Água só é aceita como água-fonte em bacia planejada e totalmente vedada; `LIGHT` exige `level=15,waterlogged=false`; flores precisam passar pela checagem de apoio do Paper.
- Os arquivos definem materiais e estados de bloco; não importam texto de placas, inventários, skins nem outros dados de conteúdo associados ao bloco.

## Erros comuns

| Mensagem | Causa | Como corrigir |
|---|---|---|
| `campo 'x' nao e aceito` | JSON tem posição no mundo | Remova; a posição é escolhida com a pá |
| `id ... ja existe` | Nome repetido ou igual a um embutido | Troque o `id` |
| `material nao permitido` | Material não existe nesta versão, não é bloco ou está protegido | Use um material de bloco disponível no Paper 26.3+ e fora da lista protegida |
| `BlockData nao corresponde` | `data` com outro nome de bloco | `OAK_DOOR` precisa de `minecraft:oak_door[...]` |
| `JSON invalido` | Vírgula sobrando, aspas erradas ou texto fora do JSON | Peça à IA para devolver só o JSON |
| `planta invalida: ...` | Parte mal formada (from > to, falta `height`, etc.) | Confira o campo indicado |

Os erros aparecem no console ao iniciar o servidor e no comando `pre-moldado recarregar`.
Depois de corrigir, rode o `recarregar` de novo; não é preciso reiniciar o servidor.

## Segurança

Como o servidor é usado por crianças, mantenha as construções sem armas, sem textos, sem cabeças
e sem conteúdo inadequado. Quem recebe o JSON só precisa carregá-lo: o plugin não executa código
do arquivo, só interpreta a planta de blocos.

## Previa visual no navegador

Na pasta `preview/` existe o arquivo `index.html`. Abra-o direto no navegador (duplo clique,
sem servidor). Ele mostra os pre-moldados embutidos e os seus, com a geometria que o construtor
vai colocar, e a lista de JSON recusados em vermelho.

- O plugin revalida a pasta a cada 5 segundos: ao salvar, editar ou apagar um `.json`, a
  previa e atualizada sozinha (a pagina confere de 2 em 2 segundos).
- Use o seletor para trocar de modelo, o controle de altura para ver andar a andar e o botao
  Girar para ver por outro lado.
- `preview/index.html` e do plugin: ele e atualizado a cada inicio do servidor. Nao edite esse
  arquivo; ele e sobrescrito. `preview/data.js` tambem e gerado pelo plugin.
