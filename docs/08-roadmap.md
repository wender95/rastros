# 8. Roadmap

## O que foi entregue

### Fase 1 — Rastreio de OS
- Abertura de OS com fluxos paralelos; receber, despachar e devolver pela matriz de transição.
- Locais de espera (Prateleira, Pátio) e liberação pelo comercial; Financeiro recebe e conclui.
- Eventos imutáveis, consulta com linha do tempo, cancelamento com motivo.
- Perfis de acesso validados no servidor; tela *Meu setor* mínima para o operador.

### Fase 2 — Agenda dos adesivadores
- Grade em horários reais, arrastar/redimensionar/trocar, copiar/recortar/colar, desfazer persistido.
- Reempilhamento "em onda", horário escolhido respeitado, bloqueios de falta e férias.
- Vínculo carro ↔ OS com andamento do material; importação do Google Sheets.
- Edição das colunas (adesivadores) sem perder histórico.

### Fase 3 — Indicadores
- Produtividade por setor e por pessoa; relatório com score lançado na linha do serviço.
- Painel por dia/semana/mês/ano.
- Tempo em horário comercial, cadastro de feriados, navegação por mês com a semana cortada.

### Fase 4 — Operação
- Um único `.jar`; sobe com o Windows; backup diário e antes de cada migração; restauração.
- Migrações Flyway (V1–V20); PostgreSQL comprovado em teste.
- Uso no celular: telas responsivas, *Minha agenda* do adesivador, app instalável.
- A Frota trabalha só pela própria agenda; a agenda acompanha a Frota sozinha; o painel é um
  reflexo da agenda do dia.
- Usuário em mais de um setor (escolhe em qual está; a Frota dá a *Minha agenda*).

### Fase 5 — No ar e integrado
- Na internet pelo Cloudflare Tunnel, no domínio próprio `rastros.cloud` (um subdomínio por
  empresa), com HTTPS e sem porta aberta; freio contra adivinhar senha.
- Extensão do navegador que importa a OS do ERP (número, cliente, serviço), sem duplicar.
- Instalação e atualização no servidor com dois cliques, cópia do banco e volta automática.

## Próximos passos

Em ordem de valor para a operação:

1. **Confiabilidade** — backup fora do servidor e monitoramento com alerta
   (detalhes em [09 — Caminho para produção](./09-caminho-para-producao.md)).
2. **Segurança** — desligar o *agir como* ao fim da fase de teste; avaliar o Cloudflare Access.
3. **Notificações** — avisar o adesivador quando o material do carro dele fica pronto.
4. **Motivos padronizados** para devoluções, para medir retrabalho por causa.
5. **Relatórios exportáveis** (planilha/PDF) do mês, para a folha de pontos.
