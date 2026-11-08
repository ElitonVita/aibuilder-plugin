# Entrada e limites dos modelos

- Altura máxima de modelos: `construction.max-height` (padrão 200, configurável). Altura automática de seleção: `zone.build-height` (padrão 100).
- Blocos por modelo: `max-project-blocks` (padrão 2500, configurável). Dimensões padrão máximas: 200×200×200; limites completos em `LIMITES-CONFIGURAVEIS.md`.
- Direção persistente por construtor: `/construtor <nome> orientacao norte|sul|leste|oeste`. Use `original` para conservar a entrada original. Disponível também nos botões do menu Pré-moldados.
- Uma única obra: `/construtor <nome> pre-moldado <id> oeste`.
- O sol nasce a Leste (+X) e se põe a Oeste (-X). Norte é -Z; Sul é +Z.
- JSON: `"entrance": "south"` descreve a entrada ORIGINAL (padrão Sul). Também aceita `north/east/west`. A geometria precisa corresponder ao campo.
- Portas, cama, escadas, eixos de troncos e conexões giram junto com a planta. A confirmação usa a geometria girada e verifica a seleção.
- Preferência afeta novas plantas blueprint. Obras existentes, incrementos absolutos e `projeto colocar` mantêm suas coordenadas.
- Ao alterar o arquivo config.yml, `/construtor reload` relê configuração e modelos, interrompendo trabalhos ativos.

Estes recursos exigem o JAR atualizado. Arquivos antigos sem `entrance` continuam válidos como Sul.
