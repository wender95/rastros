# RastrOS

**Rastreabilidade de Ordens de Serviço e agenda de instalação para uma empresa de
comunicação visual** — em produção na internet, no domínio
próprio `rastros.cloud` (um subdomínio por empresa usuária), usado todo dia pela equipe no
computador e no celular desde setembro de 2026.

Kotlin · Spring Boot 3 · React 18 · TypeScript · H2 / PostgreSQL · Flyway · JWT · Cloudflare Tunnel

---

## O problema

Numa gráfica de comunicação visual, cada pedido passa por criação, impressão, recorte,
acabamento e pela frota que adesiva os veículos. Antes do sistema:

- ninguém sabia com certeza **onde estava** uma OS — perguntava-se no corredor;
- o tempo **parado na fila** se misturava com o tempo **de trabalho**, escondendo o gargalo;
- a agenda dos adesivadores vivia numa **planilha**, sem ligação com o andamento do material,
  e a carga de cada um era conta de cabeça.

## A solução

| | |
| :--- | :--- |
| **A OS vem do ERP** | Uma extensão de navegador lê a OS aberta no ERP da empresa — número, cliente, serviço — e a importa com um clique, já com os fluxos de cada material. OS repetida não cria nada: o vendedor recebe o aviso de que ela já foi enviada. *(A extensão é específica do ERP da empresa e fica fora desta versão pública.)* |
| **Rastreio por setor** | Cada OS anda de setor em setor pelos caminhos permitidos (matriz de transição). Receber, despachar, devolver, concluir: cada passo vira um **evento imutável** — quem, quando, de onde, para onde. |
| **Tela mínima para quem produz** | O operador vê só o seu setor e três botões: *Receber*, *Devolver*, *Despachar*. Quem trabalha em mais de um setor escolhe em qual está agora; se um deles é a Frota, ganha também a *Minha agenda*. |
| **Agenda dos adesivadores** | A grade que substituiu a planilha, no formato dela: o dia em 5 espaços de trabalho, o mês inteiro numa página. Arrastar pela alça do card, selecionar células como numa planilha, esticar, trocar, dividir, marcar indisponível, **desfazer persistido**. |
| **A agenda acompanha a produção** | Cada carro liga-se à OS do material: a grade mostra se está pronto, e quando a Frota recebe ou entrega a OS o carro muda sozinho para *Executando* ou *Concluído*. |
| **Minha agenda, no celular** | Cada adesivador vê só os próprios projetos do dia e **inicia, pausa e conclui** cada um; a OS é recebida na Frota e vai para o Pátio sozinha, acompanhando o projeto. |
| **Painel ao vivo** | Reflexo da agenda de hoje: o projeto em que cada adesivador está (um serviço que atravessa dias continua em destaque até ser concluído), as OS esperando no Acabamento e a previsão do tempo. Atualiza sozinho, sem recarregar. |
| **Indicadores no próprio sistema** | Produtividade por setor e por pessoa, relatório semanal com início, pausas, conclusão e a pontuação de cada adesivador — tudo calculado na leitura a partir dos eventos, **em horário comercial**. |

Cinco perfis (Administrador, Diretoria, Comercial, Financeiro, Operacional), com as
permissões validadas **no servidor**, rota por rota.

## Em produção

```
 Celular / computador (qualquer lugar)          Extensão no navegador do vendedor
        │  https://empresa.rastros.cloud                │  lê a OS no ERP
        ▼                                                ▼
 Cloudflare (DNS do domínio rastros.cloud, HTTPS, túnel) ─┘
        │  Cloudflare Tunnel — conexão de saída, nenhuma porta aberta no roteador
        ▼
 Servidor Windows da empresa
   └── RastrOS: um único .jar (API /api/** + tela), sobe com o Windows,
        backup diário, atualização com volta automática se a versão nova não subir
```

- **Domínio próprio, um subdomínio por empresa.** O `rastros.cloud` foi comprado para o
  sistema; cada empresa usuária ganha o seu endereço, e o sistema só aceita chamadas do
  próprio (CORS restrito ao subdomínio dela e à extensão).
- **HTTPS e nada exposto.** O Cloudflare Tunnel publica o servidor da empresa sem IP fixo e
  sem abrir porta; o certificado é do Cloudflare.
- **Freio contra adivinhar senha.** 5 erros seguidos no mesmo usuário, ou 20 do mesmo
  endereço, fecham o login por 15 minutos — atrás do túnel, o endereço real vem do
  `CF-Connecting-IP`.
- **Tempo real que atravessa o túnel.** O painel recebe as mudanças por *Server-Sent
  Events*, sem *buffer* e com batida a cada 25 s, abaixo do limite de conexão parada do
  Cloudflare.
- **Instalar e atualizar sem técnico.** `servidor/SERVIDOR-INSTALAR.ps1` registra o sistema e
  o túnel; para atualizar, basta parar, trocar o `app.jar` e ligar (dois cliques) — com cópia
  do banco antes. Passo a passo em [`servidor/SERVIDOR-LEIA-ME.txt`](./servidor/SERVIDOR-LEIA-ME.txt).

## Decisões técnicas que valem destaque

- **Eventos imutáveis, indicadores derivados.** A tabela de eventos só recebe inserções; nenhum
  número é guardado em paralelo. Corrigir uma regra — um feriado, a sexta terminando às 17h —
  corrige o histórico inteiro na hora.
- **Tempo em horário comercial.** Uma OS que chega sexta às 16:00 e é recebida segunda às 08:00
  esperou 1h30, não 64 horas. O relógio pula noites, almoço, fins de semana e feriados.
- **A agenda é uma régua contínua de faixas.** Cada dia tem 9 faixas de horário reais; um
  serviço longo atravessa o almoço e a noite sem se partir. O mesmo cálculo roda no servidor
  (autoridade) e no navegador (prévia instantânea), e os testes garantem que batem.
- **O painel é um reflexo da agenda.** O que está na agenda de hoje aparece no painel; o que
  foi encerrado direto na agenda sai dele, e o relatório mostra um **?** no horário de
  conclusão — ficou registrado que não foi o adesivador quem concluiu.
- **Reempilhamento "em onda".** Ao mexer num carro, só são empurrados os que ele alcança;
  horários escolhidos e sobreposições antigas que ninguém tocou ficam onde estão.
- **Desfazer por retrato.** Antes de cada alteração, o trecho afetado da agenda é fotografado
  no banco — o Ctrl+Z desfaz também os carros que foram empurrados, e sobrevive a um reinício.
- **Concorrência sem surpresa.** Duas pessoas movimentando a mesma OS ao mesmo tempo: o
  controle de versão otimista (`@Version`) deixa só uma gravar, e a outra recebe o aviso.
- **Migrações com rede de proteção.** Flyway versiona o esquema (V1–V20); antes de aplicar uma
  migração pendente o sistema faz backup do banco, e um teste compara o *hash* de cada
  migração já publicada — editar uma delas quebra o build, não a produção.
- **Um banco para começar, outro para crescer.** H2 em arquivo roda num servidor só; a mesma
  aplicação sobe num PostgreSQL de verdade num teste automatizado.

## Qualidade

- **220 testes no backend** (JUnit 5): regras de cada tela, permissões de cada perfil por HTTP,
  a agenda (troca, bordas, carga, sexta 17h, corte do mês), o painel como reflexo da agenda,
  usuário com mais de um setor, importação da planilha conferida semana a semana, número fixo
  de consultas SQL por tela, backup que restaura, duas pessoas movimentando a mesma OS ao mesmo
  tempo e a aplicação inteira sobre um **PostgreSQL 14 embutido**.
- **30 testes no frontend** (Vitest): as contas da grade no navegador batem com as do servidor.
- O pacote de produção **não é gerado** se um teste falhar.

## Desenvolver

Pré-requisitos: JDK 21+, Node.js 20+ e Maven 3.9 — os scripts acham cada um sozinhos, sem
caminho fixo.

```bash
powershell -ExecutionPolicy Bypass -File .\iniciar-backend.ps1
```

```bash
powershell -ExecutionPolicy Bypass -File .\iniciar-frontend.ps1
```

A tela abre em <http://localhost:5173>, com recarga instantânea. Para gerar o pacote de
produção (`gerar-pacote.ps1`), rodar os testes, backup, PostgreSQL e todas as telas, veja o
**[guia de operação](./COMO-EXECUTAR.md)**.

### Demonstração, com dados fictícios

Para ver o sistema "em operação", suba a API com a carga de demonstração (o padrão do
`iniciar-backend.ps1`) e a tela com o `iniciar-frontend.ps1`, e abra <http://localhost:5173>.
Num banco novo, o sistema já nasce com umas **seis semanas de operação coerente**:

- **4 adesivadores** com a agenda preenchida como na operação real: **vários serviços por
  dia**, uma cópia por espaço (como a alça que replica deixa), caminhões e ônibus passando
  para o dia seguinte, um dia Indisponível de vez em quando. Cada serviço tem **início e
  conclusão registrados pelo adesivador**, um depois do outro, às vezes com pausa;
- **Hoje, no painel**: o que cada um já concluiu, o que está fazendo (**Agora**) e o que vem
  depois — um adesivador pausado, um que começou outro sem concluir o anterior e um
  caminhão que começou ontem e continua em andamento;
- **OS no formato do ERP** (número de 5 dígitos, razão social e serviço), cada uma ligada ao
  projeto da agenda e passando pelos setores pelo caminho certo: Criação, Impressão,
  Recorte ou Preparação, Frota, Pátio e Financeiro — além de placas e banners pelo
  Acabamento e pela Prateleira.

Todos os usuários entram com a senha `123456` (o sistema pede para trocar no primeiro
acesso): `admin`, `diretoria`, `vendedor`, `vendedor2`, `financeiro`, os operadores
`criacao`, `impressao`, `recorte`, `preparacao`, `acabamento` e os adesivadores `frota`,
`lucas`, `rafael` e `tiago` (a *Minha agenda* de cada um). Todos os nomes, empresas e
números são fictícios.

## Estrutura

```
backend/     API Kotlin/Spring Boot, migrações Flyway e testes
frontend/    Tela React/TypeScript e testes Vitest
servidor/    Instalação no servidor da empresa: serviço do Windows, Cloudflare Tunnel, atualização
docs/        Especificação: processo, perfis, requisitos, regras, indicadores, modelo de dados
scripts/     Utilitários
*.ps1        Desenvolver, gerar o pacote, backup/restauração, importar a planilha
```

## Documentação

1. [Visão geral](./docs/01-visao-geral.md)
2. [Análise do processo](./docs/02-analise-do-processo.md)
3. [Perfis e permissões](./docs/03-stakeholders-e-permissoes.md)
4. [Requisitos](./docs/04-requisitos.md)
5. [Regras de negócio](./docs/05-regras-de-negocio.md)
6. [Matriz de transição](./docs/06-fluxos-e-matriz-de-transicao.md)
7. [Indicadores](./docs/07-kpis-e-analytics.md)
8. [Roadmap](./docs/08-roadmap.md)
9. [Caminho para produção](./docs/09-caminho-para-producao.md)
10. [Modelo de dados](./docs/10-modelo-de-dados.md)

Operação do dia a dia: [COMO-EXECUTAR.md](./COMO-EXECUTAR.md).

## Status

**No ar**, na internet, com domínio próprio e HTTPS, usado pela operação da empresa no
computador e no celular — as OS chegam do ERP pela extensão e a agenda da frota substituiu a
planilha. O que ainda falta — backup fora do servidor e monitoramento com alerta — está em
[Caminho para produção](./docs/09-caminho-para-producao.md).

---

Desenvolvido por **Wender**, com a análise do processo feita junto da operação.
