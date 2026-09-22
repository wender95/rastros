# 7. Indicadores

Os indicadores são calculados **dentro do sistema, na hora da leitura**, a partir da
trilha de eventos e da agenda. Nada é armazenado em paralelo: mudar uma regra (um feriado
novo, a sexta terminando às 17h) corrige o histórico inteiro na hora. O banco continua
aberto para uma ferramenta de BI, se um dia for preciso ir além.

## Tempo em horário comercial

Todo tempo de OS é medido com a empresa aberta — segunda a sexta, 07:30–12:00 e
13:30–18:00, sexta até 17:00, fora feriados (44 h por semana). Uma OS que chega sexta às
16:00 e é recebida segunda às 08:00 esperou **1h30**, e não 64 horas.

## Por setor

| Indicador | Cálculo |
| :--- | :--- |
| **Tempo de espera** | recebimento no setor − chegada no setor |
| **Tempo de processamento** | saída do setor − recebimento no setor |
| **Tempo no setor** | saída − chegada (espera + processamento) |
| **Processadas** | OS que saíram do setor no período |
| **Retornos** | saídas do tipo devolução |

Dentro de cada setor, os mesmos números **por pessoa** — a OS conta para quem a recebeu.

## Comercial e Financeiro

* **OS abertas** por vendedor no período.
* **Concluídas** no Financeiro e o tempo até concluir.
* **Prateleira e Pátio**: quantas saíram e quanto tempo esperaram a liberação.

## Adesivadores

* **Serviços** feitos e **concluídos** no período.
* **Pontos (score)** — o peso de cada serviço, lançado no relatório; o total soma só os
  concluídos, e "pontos lançados" soma todos os que já têm score.
* **Carga da semana** — horas ocupadas na grade contra a capacidade (44 h, menos
  indisponibilidades).

## Onde aparecem

| Tela | Período | O que mostra |
| :--- | :--- | :--- |
| **Painel** | dia, semana, mês ou ano | agora (ativas, na fila, em curso por setor) e no período (abertas, concluídas, canceladas, saídas por setor) |
| **Produtividade** | mês inteiro ou semana do mês | setores e pessoas, com gráficos ou tabelas |
| **Relatório** | semana do mês ou mês inteiro | cada serviço de cada adesivador, com o score, e os pontos da equipe |
| **Agenda** | semana do mês | carga de cada adesivador no topo da coluna |
