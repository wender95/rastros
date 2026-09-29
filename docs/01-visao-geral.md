# 1. Visão Geral do Projeto

## 1.1 Problema de negócio

Numa empresa de comunicação visual, cada pedido passa por vários setores — criação,
impressão, recorte, preparação, acabamento, frota — antes de chegar ao cliente e ao
financeiro. Sem rastreabilidade:

* ninguém sabe com certeza **onde está** uma Ordem de Serviço (OS) — pergunta-se no
  corredor ou no WhatsApp;
* o tempo que a OS **esperou na fila** se mistura com o tempo em que alguém **trabalhou**
  nela, e o gargalo real fica invisível;
* a agenda dos adesivadores vive numa planilha: difícil de remanejar, sem ligação com o
  andamento do material, e com contas de carga feitas à mão;
* atrasos não têm causa auditável — não há registro de quem recebeu, quando e de quem.

## 1.2 O que o sistema faz

O **RastrOS** é o sistema de chão de fábrica da empresa:

1. **Rastreio de OS por setor** — cada OS entra pelo comercial e anda de setor em setor
   pelos caminhos permitidos. Cada passo (abrir, receber, despachar, devolver, concluir,
   cancelar) é um evento imutável com quem, quando, de onde e para onde.
2. **Agenda dos adesivadores** — a grade que substituiu a planilha, no formato dela: o dia
   em 5 horários, sem relógio, com o mês inteiro numa página. Arrastar, esticar pela alça,
   trocar de lugar, copiar/colar (que divide o serviço em partes), desfazer. Cada carro pode
   estar ligado à OS que produz o material, e a agenda acompanha a Frota sozinha. A grade
   antiga, por faixa de horário, continua disponível **só para o administrador**.
3. **Indicadores dentro do sistema** — painel por dia/semana/mês/ano, produtividade por
   setor e por pessoa, relatório com a pontuação (score) de cada adesivador. Tudo calculado
   na hora a partir dos eventos, contando só o horário comercial.

## 1.3 Premissas e limites

* **O ERP continua sendo o sistema oficial** de vendas e faturamento. O RastrOS não
  emite nada: o número da OS vem do ERP e é informado ao abrir o fluxo.
* **Eventos são fatos, não se editam.** O histórico é a fonte de verdade; os indicadores
  são derivados dele na leitura, nunca guardados em paralelo.
* **Interface mínima para quem produz.** O operador vê só o seu setor e três botões
  (Receber, Devolver, Despachar); o adesivador vê só a própria agenda, no celular.
* **Regras valem no servidor.** Esconder um botão é conforto; quem barra de verdade é a API.
* **Tempo é horário comercial.** Segunda a sexta, 07:30–12:00 e 13:30–18:00 (sexta até
  17:00), fora feriados: 44 horas por semana.

## 1.4 Situação

Em uso na operação da empresa desde setembro de 2026, rodando numa máquina da rede
interna, acessado por computador e celular. O caminho para a produção plena está em
[09 — Caminho para produção](./09-caminho-para-producao.md).
