# 6. Matriz de Transição de Setores

A matriz define para onde uma OS pode ir a partir de onde está. Ela é **parametrizável**
(Administração → Matriz) e validada no servidor a cada despacho. Esta é a configuração em
uso:

| Setor de origem | Destinos permitidos | Quem executa |
| :--- | :--- | :--- |
| **Entrada (Comercial)** | Criação, Recorte, Impressão, Preparação, Frota, Acabamento | Comercial, Diretoria, Admin |
| **Criação** | Recorte, Impressão, Preparação, Frota, Acabamento | Operador da Criação |
| **Impressão** | Recorte, Preparação, Frota, Acabamento | Operador da Impressão |
| **Recorte** | Preparação, Frota, Acabamento | Operador do Recorte |
| **Preparação** | Frota | Operador da Preparação |
| **Acabamento** | Prateleira | Operador do Acabamento |
| **Frota** | Pátio | Adesivador (pela Minha agenda) |
| **Prateleira** | Financeiro | Comercial, Diretoria, Admin |
| **Pátio** | Financeiro | Comercial, Diretoria, Admin |
| **Financeiro** | — (conclui) | Perfil Financeiro |

## Devolver

Além dos destinos acima, um setor que **recebeu** a OS pode devolvê-la ao setor de onde ela
veio — por exemplo, material com defeito volta da Frota para a Impressão. O retorno fica
registrado como evento `RETORNO`. O botão só aparece quando o setor anterior não é um
destino normal, para não haver "devolver" em pingue-pongue.

## Estados de um fluxo

```
            receber               despachar / devolver
AGUARDANDO_RECEBIMENTO ──▶ EM_PROCESSAMENTO ──▶ AGUARDANDO_RECEBIMENTO (próximo setor)
                                  │
                                  └── Financeiro: concluir ──▶ ENCERRADA

Qualquer estado ativo ── cancelar (com motivo) ──▶ CANCELADA
```

Prateleira e Pátio não têm recebimento: a OS chega e espera ser liberada.
