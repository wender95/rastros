# 5. Regras de Negócio

## Rastreio de OS

* **RN01 — Primeiro recebimento é o responsável.** Quem recebe a OS no setor fica registrado
  como responsável pela etapa; é a ele que o tempo no setor é atribuído na produtividade.
* **RN02 — Histórico não se apaga.** Cancelar muda o status para `CANCELADA` e guarda o
  motivo; todos os eventos anteriores permanecem.
* **RN03 — Fluxos independentes.** Os fluxos de uma mesma OS têm status, responsável e tempos
  próprios.
* **RN04 — Destino só pela matriz.** O setor de destino é escolhido entre as opções da
  [matriz de transição](./06-fluxos-e-matriz-de-transicao.md), nunca digitado.
* **RN05 — Locais de espera.** Acabamento vai sempre para a Prateleira; Frota, sempre para o
  Pátio. Da Prateleira e do Pátio para o Financeiro, só Comercial, Diretoria ou Admin.
* **RN06 — O Financeiro conclui.** Chegar ao Financeiro não encerra nada: a OS espera ser
  recebida, e só o Financeiro conclui.
* **RN07 — Devolver sem pingue-pongue.** Devolver manda a OS para o setor de onde veio e fica
  registrado como retorno; o botão só aparece quando esse setor não é um destino normal.

## Agenda

* **RN08 — Horário escolhido é respeitado.** Um carro fica onde foi colocado; buracos são
  permitidos. Ninguém sobe para ocupar um buraco.
* **RN09 — O empurrão só alcança quem a edição atingiu.** Ao crescer, mover ou trocar, só
  descem os carros que encostam no editado (em onda); sobreposições antigas que ninguém tocou
  ficam como estão.
* **RN10 — Almoço e sexta 17h não recebem serviço.** O almoço (12:00–13:30) e a sexta das
  17:00 às 18:00 não recebem início de carro; um serviço longo pula os dois e continua.
* **RN11 — A troca preserva os horários.** Arrastar um carro sobre outro troca os dois; cada
  um leva a sua **quantidade de horários** (as horas se ajustam, porque as faixas das 07:30 e
  13:30 têm 1h30). Na mesma coluna, os dois trocam de ordem e ficam encostados.
* **RN12 — A carga nunca passa da capacidade.** A carga da semana conta só as horas que caem
  nos dias mostrados, inclusive de quem começou antes.
* **RN13 — A agenda acompanha a Frota.** Frota recebe a OS → carro *Executando*; entrega no
  Pátio → *Concluído*; devolve → *Programado*. Status marcados à mão (*Não veio*, *Externo*,
  *Concluído*) não são alterados.
* **RN14 — Remover adesivador não apaga história.** Quem tem carro de hoje em diante não sai;
  quem só tem passado sai da agenda mas continua nas semanas em que trabalhou.
* **RN15 — O score se lança no relatório.** A pontuação é o peso do que foi entregue; o total
  soma só os serviços concluídos.

## Tempo e períodos

* **RN16 — Horário comercial.** Todo tempo de OS conta só com a empresa aberta: segunda a
  sexta, 07:30–12:00 e 13:30–18:00 (sexta até 17:00), fora feriados cadastrados. "Dia" nas
  telas é dia útil de 9 h.
* **RN17 — Mês cortado na virada.** Agenda, painel, produtividade e relatório navegam por mês;
  a semana que cai em dois meses é dividida entre eles, como nas abas da planilha antiga.
