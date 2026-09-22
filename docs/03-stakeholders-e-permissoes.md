# 3. Stakeholders e Perfis de Acesso

Cada perfil vê só o que é dele. A regra vale **no servidor, rota por rota** — o menu da
tela só espelha o que a API permite (teste `AcessosTest`).

| Perfil | Quem é | O que usa |
| :--- | :--- | :--- |
| **Administrador** | TI / quem mantém o sistema | Tudo: painel, agenda (e edição dos adesivadores), pátio e prateleira, produtividade, relatório, consultar e abrir OS, **administração** (usuários, matriz de transição, feriados, backups) |
| **Diretoria** | Diretores e gerência | Tudo do nível de usuário: painel, agenda (e edição dos adesivadores), pátio e prateleira, produtividade, relatório, consultar e abrir OS |
| **Comercial** | Vendedores | Painel, agenda, pátio e prateleira (libera para o Financeiro), consultar e abrir OS |
| **Financeiro** | Financeiro | Tela **Meu setor** do Financeiro (recebe e é o **único que conclui**), painel, cadastro de usuários e redefinição de senha — sem criar nem alterar administrador |
| **Operacional** | Funcionários dos setores | Só a tela **Meu setor**: receber, devolver e despachar as OS do próprio setor |

## Casos especiais

* **Frota** — quem é da Frota e tem uma coluna na agenda **não vê a tela Meu setor**: trabalha
  só pela **Minha agenda**, onde recebe, entrega no Pátio ou devolve a OS de cada carro seu.
* **Adesivador** — a coluna da agenda é da pessoa pelo nome (a coluna *ANDRE* é do usuário
  *Andre*). Quem tem coluna e é de outro setor vê a própria agenda, mas não recebe pela Frota.
* **Prateleira e Pátio** não têm operador: só o Comercial, a Diretoria e o Administrador tiram
  uma OS de lá.
* **Excluir usuário** só é possível para quem nunca movimentou nada; quem já trabalhou é
  desativado, para o histórico continuar dizendo quem fez cada coisa.

## Contas e senhas

* Entra-se com **nome de usuário** (ex.: `joao.silva`); e-mail é opcional.
* A senha definida pelo administrador é **provisória**: no primeiro acesso a pessoa só vê a
  tela de troca, e o servidor recusa qualquer outra chamada até a troca.
* A senha nova precisa de 8 caracteres e não pode ser óbvia nem igual ao usuário.
