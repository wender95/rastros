# 2. Análise do Processo Operacional

## 2.1 O ciclo de uma OS

1. **Comercial** abre a OS no sistema com o número do ERP e escolhe por onde cada item
   começa (Criação, Recorte, Impressão, Preparação, Frota ou Acabamento).
2. **Setores de produção** — cada setor **recebe** a OS (assume a responsabilidade), trabalha
   e **despacha** para o próximo setor permitido, ou **devolve** para quem mandou.
   * **Criação** — arte e aprovação.
   * **Impressão** e **Recorte** — produção do material.
   * **Preparação** — prepara o veículo; segue sempre para a Frota.
   * **Acabamento** — finaliza peças (placas, adesivos, banners); segue sempre para a Prateleira.
   * **Frota** — os adesivadores aplicam o material no veículo; segue sempre para o Pátio.
3. **Locais de espera** — sem operador, só guardam:
   * `PRATELEIRA`: peças prontas aguardando retirada ou entrega;
   * `PÁTIO`: veículos prontos aguardando o cliente.
4. **Liberação** — o **Comercial** (ou a Diretoria) tira a OS da Prateleira ou do Pátio e a
   envia ao **Financeiro**.
5. **Financeiro** — recebe a OS como qualquer setor e é o **único que conclui** o fluxo.

```
Comercial ──▶ Criação ──▶ Impressão ──▶ Recorte ──▶ Acabamento ──▶ PRATELEIRA ─┐
                 │            │            │                                  ├─▶ Financeiro ──▶ concluída
                 └──────▶ Preparação ──▶ Frota ─────────────────▶ PÁTIO ──────┘
```

(Os caminhos exatos estão na [matriz de transição](./06-fluxos-e-matriz-de-transicao.md).)

## 2.2 Vários itens na mesma OS (fluxos paralelos)

Uma OS pode ter itens independentes, cada um com seu **fluxo**:

* **OS 5050**
  * Fluxo A — *Lona impressa*: Impressão → Acabamento → Prateleira → Financeiro.
  * Fluxo B — *Adesivação da frota*: Frota → Pátio → Financeiro.

Cada fluxo tem status, responsável e tempos próprios.

## 2.3 A agenda e a Frota

A Frota trabalha por agenda: cada adesivador é uma coluna, cada carro um bloco de horas.
O carro pode estar **ligado à OS** que produz o material. Com isso:

* a agenda mostra se o material do carro está **pronto** ou **em produção**;
* quando a Frota **recebe** a OS, o carro passa a *Executando*; quando **entrega no Pátio**,
  passa a *Concluído*; se **devolve**, volta a *Programado* — sem ninguém mexer na agenda;
* cada adesivador trabalha pela aba **Minha agenda**, no celular: vê só os próprios carros
  do dia e recebe, entrega ou devolve a OS de cada um por ali.

## 2.4 O que o processo antigo não mostrava

| Pergunta | Antes | Com o sistema |
| :--- | :--- | :--- |
| Onde está a OS 5062? | Perguntar | Consulta, com linha do tempo |
| Quanto tempo ela esperou na Impressão? | Não se sabia | Espera e processamento separados, em horas úteis |
| Quem recebeu? | Não se sabia | Registrado no evento de recebimento |
| O adesivador está com a semana cheia? | Conta na planilha | Carga da semana no topo da coluna (`41h / 44h`) |
| Quantos pontos cada um fez no mês? | Soma manual | Relatório do mês |
