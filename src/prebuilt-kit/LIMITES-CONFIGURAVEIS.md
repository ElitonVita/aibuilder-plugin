# Limites de construção configuráveis

O cubo moderno habitável é built-in e substitui a geometria antiga no mesmo ID `cubo_moderno`.
Não é necessário importar seu JSON para usá-lo com o JAR atualizado.

O servidor define os limites; os valores abaixo são padrões, não tetos fixos do plugin:

| Configuração | Padrão | Finalidade |
| --- | ---: | --- |
| `construction.max-width` | 200 | largura máxima |
| `construction.max-depth` | 200 | profundidade máxima |
| `construction.max-height` | 200 | altura máxima |
| `construction.max-volume` | 8000000 | envelope, incluindo espaço vazio |
| `max-project-blocks` | 2500 | blocos efetivamente colocados |
| `construction.max-parts` | 4096 | partes do JSON |
| `construction.max-openings` | 1024 | aberturas por parte |
| `construction.max-ai-actions` | 250 | ações individuais por resposta da IA |
| `construction.max-processing-visits` | 128000000 | expansão de partes/aberturas |
| `construction.max-file-bytes` | 67108864 | tamanho de arquivos importados/projetos/obras |
| `construction.preview-max-blocks` | 20000 | blocos desenhados na prévia |
| `zone.build-height` | 100 | altura automática inicial da área selecionada |

Um estádio pode ter envelope 200×200×200 sem ocupar 8 milhões de blocos. Aumente
`max-project-blocks` para o total real da geometria e ajuste os demais limites conforme o servidor.
Para dimensões maiores que 200, ajuste os três eixos e o volume. Limites físicos do mundo,
claims, entidades, material permitido, quota e confirmação continuam sendo conferidos.
Valores devem ser positivos e caber nos tipos numéricos da configuração.

A seleção automática ganha até `zone.build-height`, limitada por `construction.max-height`.
O menu usa esse teto configurado. Marque novamente a área após mudar a altura automática.
Após editar config.yml, use `/construtor reload`, que interrompe trabalhos ativos e revalida os modelos.
Configurações existentes preservam valores explícitos.

A prévia pode mostrar AMOSTRA VISUAL; contagem e dimensões informadas continuam sendo totais.
A amostragem afeta só o desenho no navegador. Não reduz o plano executado pelo construtor.
