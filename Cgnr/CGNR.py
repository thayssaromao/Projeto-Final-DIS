import numpy as np
import time
from PIL import Image
import os

def transformar_g_em_vetor_coluna(g):
    return np.asarray(g, dtype=np.float64).reshape(-1, 1)

def calcular_fator_reducao(H):
    """
    c = ||HᵀH||₂
    """
    H = np.asarray(H, dtype=np.float64)
    return np.linalg.norm(H.T @ H, 2)

def calcular_coeficiente_regularizacao(H, g):
    """
    λ = max(abs(Hᵀg)) * 0.10
    """
    H = np.asarray(H, dtype=np.float64)
    g = np.asarray(g, dtype=np.float64).reshape(-1, 1)

    return np.max(np.abs(H.T @ g)) * 0.10 


def calcular_erro(r_atual, r_anterior):
    """
    ϵ = ||rᵢ₊₁||₂ - ||rᵢ||₂
    """
    return np.linalg.norm(r_atual, 2) - np.linalg.norm(r_anterior, 2)

def aplicar_ganho(g):
    g = np.asarray(g, dtype=np.float64).copy()

    if g.ndim == 1:
        g = g.reshape(-1, 1)

    S, N = g.shape

    for c in range(N):
        for l in range(S):
            gamma = 100 + (1 / 20) * np.sqrt((l + 1) * (l + 1))
            g[l, c] = g[l, c] * gamma

    return g

def cgnr(H, g, tol=1e-4, max_iter=10):

    print("Iniciando CGNR...")
    inicio = time.time()

    print("Convertendo H e g...")
    H = np.asarray(H, dtype=np.float64)
    g = np.asarray(g, dtype=np.float64).reshape(-1, 1)

    qtd_linhas_H = H.shape[0]
    qtd_colunas_H = H.shape[1]

    print("Verificando dimensões...")
    print("H:", H.shape)
    print("g:", g.shape)

    if g.shape[0] != qtd_linhas_H:
        raise ValueError(
            f"Dimensões incompatíveis: H tem {qtd_linhas_H} linhas, "
            f"mas g tem {g.shape[0]} linhas."
        )

    print("Calculando fator de redução c...")
    c = calcular_fator_reducao(H)
    print("Fator de redução calculado.")

    print("Calculando lambda...")
    lamb = calcular_coeficiente_regularizacao(H, g)
    print("Lambda calculado.")

    pixels = qtd_colunas_H

    print("Inicializando vetores...")
    f = np.zeros((pixels, 1), dtype=np.float64)

    r = g - H @ f
    z = H.T @ r
    p = z.copy()

    erro = float("inf")
    iteracoes = 0

    print("Entrando nas iterações do CGNR...")

    for i in range(max_iter):

        print(f"Iteração {i + 1}/{max_iter}...")
        r_anterior = r.copy()

        print("  Calculando w = H @ p...")

        w = H @ p

        numerador_alpha = (z.T @ z)[0, 0]
        denominador_alpha = (w.T @ w)[0, 0]

        if denominador_alpha == 0:
            break

        alpha = numerador_alpha / denominador_alpha

        f = f + alpha * p
        r = r - alpha * w

        z_novo = H.T @ r

        erro = calcular_erro(r, r_anterior)
        erro_abs = abs(erro)

        iteracoes = i + 1

        if erro_abs < tol:
            z = z_novo
            break

        numerador_beta = (z_novo.T @ z_novo)[0, 0]
        denominador_beta = (z.T @ z)[0, 0]

        if denominador_beta == 0:
            z = z_novo
            break

        beta = numerador_beta / denominador_beta

        p = z_novo + beta * p
        z = z_novo

    fim = time.time()

    return {
        "f": f,
        "tempo": fim - inicio,
        "iteracoes": iteracoes,
        "erro": erro,
        "erro_abs": abs(erro),
        "fator_reducao": c,
        "lambda": lamb
    }

def salvar_imagem(f, resolucao, nome_arquivo):
    img = np.asarray(f, dtype=np.float64).reshape((resolucao, resolucao), order="F")

    # Remove valores negativos pequenos
    img = np.maximum(img, 0)

    # Normaliza entre 0 e 1
    if np.max(img) != 0:
        img = img / np.max(img)

    # Corta ruído fraco do fundo
    img[img < 0.15] = 0

    img = (img * 255).astype(np.uint8)

    imagem = Image.fromarray(img, mode="L")
    imagem.save(nome_arquivo)

def descobrir_resolucao(H):
    pixels = H.shape[1]
    resolucao = int(np.sqrt(pixels))

    if resolucao * resolucao != pixels:
        raise ValueError(
            f"H tem {pixels} colunas, não forma uma imagem quadrada."
        )

    return resolucao

def carregar_csv(nome_arquivo):
    cache = nome_arquivo + ".npy"

    if os.path.exists(cache):
        print(f"Carregando cache {cache}...", flush=True)
        return np.load(cache)

    print(f"Lendo CSV {nome_arquivo}...", flush=True)

    with open(nome_arquivo, "r") as arquivo:
        primeira_linha = arquivo.readline().strip()

    colunas = [valor for valor in primeira_linha.split(",") if valor != ""]
    quantidade_colunas = len(colunas)

    dados = np.loadtxt(
        nome_arquivo,
        delimiter=",",
        dtype=np.float64,
        usecols=range(quantidade_colunas)
    )

    np.save(cache, dados)

    print(f"CSV carregado e cache salvo: {dados.shape}", flush=True)

    return dados

if __name__ == "__main__":
    print("Carregando H...")
    H = carregar_csv("H-1.csv")

    print("Carregando g...")
    g = carregar_csv("G-1.csv")

    print("Descobrindo resolução...")
    resolucao = descobrir_resolucao(H)

    print("Resolução:", f"{resolucao}x{resolucao}")
    print("H:", H.shape)
    print("g:", g.shape)

    print("Aplicando ganho...")
    g_com_ganho = aplicar_ganho(g)

    print("Transformando g em vetor coluna...")
    g_vetor = transformar_g_em_vetor_coluna(g_com_ganho)

    print("Rodando CGNR...")
    resultado = cgnr(H, g_vetor)

    print("Salvando imagens de teste...")

    f = resultado["f"]

    nome_imagem = f"imagem_reconstruida_{resolucao}x{resolucao}.png"

    salvar_imagem(f, resolucao, nome_imagem)

    print("Tempo:", resultado["tempo"])
    print("Iterações:", resultado["iteracoes"])
    print("Erro:", resultado["erro"])
    print("Fator de redução c:", resultado["fator_reducao"])
    print("Lambda:", resultado["lambda"])

    print("Finalizado.")