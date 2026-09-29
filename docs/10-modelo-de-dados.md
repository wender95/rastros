# 10. Modelo de Dados

O esquema é criado e evoluído **só pelas migrações Flyway**, em
[`backend/src/main/resources/db/migration`](../backend/src/main/resources/db/migration)
(SQL) e [`backend/src/main/kotlin/db/migration`](../backend/src/main/kotlin/db/migration)
(migrações em Kotlin, quando a mudança precisa de lógica). Este documento é o mapa; a
verdade são as migrações.

## Diagrama

```
perfis ─┐                         setores ◀── transicoes_permitidas (origem → destino)
        ▼                            ▲
     usuarios ─────────────┐         │
        │ criado_por       │         │ setor_atual / setor_anterior
        ▼                  │         │
  ordens_servico ──1:N──▶ fluxos_os ─┘
        ▲                  │ 1:N
        │ os_id            ▼
        │           eventos_movimentacao  (imutável: quem, quando, de onde, para onde)
        │
  agendamentos ◀──N:1── adesivadores (colunas da agenda)
        │
  historico_agenda (retratos para o Desfazer)        feriados (horário comercial)
```

## Tabelas

### Rastreio de OS

| Tabela | Para que serve | Colunas principais |
| :--- | :--- | :--- |
| `perfis` | ADMIN, DIRETORIA, VENDEDOR (Comercial), FINANCEIRO, OPERACIONAL | `nome` |
| `setores` | Criação, Impressão, Recorte, Preparação, Acabamento, Frota, Prateleira, Pátio, Financeiro | `nome`, `ativo` |
| `usuarios` | Contas de acesso | `login`, `nome`, `email` (opcional), `senha_hash` (BCrypt), `perfil_id`, `setor_id`, `ativo`, `trocar_senha` |
| `transicoes_permitidas` | A matriz de transição (origem nula = entrada comercial) | `setor_origem`, `setor_destino` |
| `ordens_servico` | A OS do ERP | `numero_os_erp`, `cliente`, `servico` (o que a OS manda fazer), `criado_por`, `criado_em`, `cancelada` |
| `fluxos_os` | Cada item da OS andando pelos setores (estado atual) | `os_id`, `identificador_fluxo`, `setor_atual_id`, `setor_anterior_id`, `status_atual`, `entrou_no_setor_em`, `recebido_por_id`, `recebido_em`, `encerrado`, `encerrado_em` |
| `eventos_movimentacao` | **A trilha imutável**: tudo que aconteceu | `fluxo_id`, `tipo_evento` (CRIACAO, RECEBIMENTO, DESPACHO, RETORNO, ENTREGA, CONCLUSAO, CANCELAMENTO), `setor_origem_id`, `setor_destino_id`, `usuario_id`, `data_hora`, `observacao` |

`fluxos_os` guarda só o **estado atual** (para as filas serem rápidas); todo indicador de
tempo sai de `eventos_movimentacao`.

### Agenda

| Tabela | Para que serve | Colunas principais |
| :--- | :--- | :--- |
| `adesivadores` | As colunas da agenda (adesivadores, Encaixe, Noturno) | `nome`, `tipo`, `ordem`, `ativo`, `usuario_id` (vínculo opcional com a conta) |
| `agendamentos` | Cada carro ou bloqueio na grade | `data`, `adesivador_id`, `slot_inicio` (faixa 1–9), `horas_estimadas`, `tipo` (SERVICO/INDISPONIVEL), `descricao`, `vendedor_codigo`, `status`, `score`, `os_id`, `observacao`, `grupo_id` (partes do mesmo serviço) |
| `historico_agenda` | Retrato de um trecho da agenda antes de cada alteração — o **Desfazer** (25 passos por pessoa, 24 h) | `usuario_id`, `descricao`, retrato serializado |

Um carro não guarda "até que horas vai": o fim sai de `slot_inicio` + `horas_estimadas`
percorrendo a régua de faixas, pulando almoço, sexta 17h–18h e fins de semana.

### Apoio

| Tabela | Para que serve |
| :--- | :--- |
| `feriados` | Dias em que a empresa não abre; saem do tempo das OS |
| `flyway_schema_history` | Controle das migrações (Flyway) |

## Migrações

| Versão | O que fez |
| :--- | :--- |
| V1 | Esquema inicial |
| V2 | Enums como texto |
| V3 | Encurta serviços importados que invadiam o seguinte (Kotlin) |
| V4 | Histórico do Desfazer no banco |
| V5 | Troca de senha obrigatória |
| V6 | Perfil Financeiro |
| V7 | Login por nome de usuário (Kotlin) |
| V8 | Feriados, com os nacionais de 2026 e 2027 |
| V9 | Sexta até as 17:00: carros que ocupavam 17h–18h de sexta perdem essa hora (Kotlin) |

Antes de aplicar qualquer migração pendente, o sistema faz um **backup automático** do banco.
