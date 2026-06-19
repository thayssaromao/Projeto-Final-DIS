import numpy as np
import time
import random
from PIL import Image
import os
import scipy.sparse as sp


def carregar_matriz_esparsa(nome_arquivo):
    cache = nome_arquivo + ".npz"

    if os.path.exists(cache):
        print(f"Carregando matriz esparsa do cache {cache}...")
        return sp.load_npz(cache)

    print(f"Convertendo CSV {nome_arquivo} para matriz esparsa...")

    linhas = []
    colunas = []
    valores = []

    with open(nome_arquivo, "r") as arquivo:
        for i, linha in enumerate(arquivo):
            partes = linha.strip().split(",")

            for j, valor in enumerate(partes):
                if valor.strip() == "":
                    continue

                valor_float = float(valor)

                if valor_float != 0.0:
                    linhas.append(i)
                    colunas.append(j)
                    valores.append(valor_float)

    qtd_linhas = max(linhas) + 1
    qtd_colunas = max(colunas) + 1

    H = sp.csr_matrix(
        (valores, (linhas, colunas)),
        shape=(qtd_linhas, qtd_colunas),
        dtype=np.float64
    )

    sp.save_npz(cache, H)

    print("Matriz esparsa salva em cache:", H.shape)

    return H

def calcular_coeficiente_regularizacao(H, g):
    """
    λ = max(abs(Hᵀg)) * 0.10
    """
    g = np.asarray(g, dtype=np.float64).reshape(-1)

    Htg = H.T @ g

    return np.max(np.abs(Htg)) * 0.10


def cgnr(H, g, tol=1e-4, max_iter=10):
    inicio = time.time()

    g = np.asarray(g, dtype=np.float64).reshape(-1)

    if H.shape[0] != g.shape[0]:
        raise ValueError(
            f"Dimensões incompatíveis: H tem {H.shape[0]} linhas, "
            f"mas g tem {g.shape[0]} elementos."
        )

    lamb = calcular_coeficiente_regularizacao(H, g)

    f = np.zeros(H.shape[1], dtype=np.float64)

    r = g.copy()
    z = H.T @ r
    p = z.copy()

    norm_z_sq = float(z @ z)

    erro = float("inf")
    iteracoes = 0

    for i in range(max_iter):
        iteracoes = i + 1

        w = H @ p
        norm_w_sq = float(w @ w)

        if norm_w_sq == 0.0:
            break

        alpha = norm_z_sq / norm_w_sq

        f = f + alpha * p
        r = r - alpha * w

        erro = np.linalg.norm(r)

        if erro < tol:
            break

        z_novo = H.T @ r
        norm_z_novo_sq = float(z_novo @ z_novo)

        if norm_z_sq == 0.0:
            break

        beta = norm_z_novo_sq / norm_z_sq

        p = z_novo + beta * p
        z = z_novo
        norm_z_sq = norm_z_novo_sq

    fim = time.time()

    return {
        "f": f,
        "tempo": fim - inicio,
        "iteracoes": iteracoes,
        "erro": erro,
        "erro_abs": abs(erro),
        "lambda": lamb
    }

def cgne(H, g, tol=1e-4, max_iter=10):
    inicio = time.time()

    g = np.asarray(g, dtype=np.float64).reshape(-1)

    if H.shape[0] != g.shape[0]:
        raise ValueError(
            f"Dimensões incompatíveis: H tem {H.shape[0]} linhas, "
            f"mas g tem {g.shape[0]} elementos."
        )

    lamb = calcular_coeficiente_regularizacao(H, g)

    f = np.zeros(H.shape[1], dtype=np.float64)

    r = g.copy()
    p = H.T @ r

    erro = float("inf")
    iteracoes = 0

    for i in range(max_iter):
        iteracoes = i + 1

        norm_r_sq = float(r @ r)
        norm_p_sq = float(p @ p)

        if norm_p_sq == 0.0:
            break

        alpha = norm_r_sq / norm_p_sq

        f = f + alpha * p

        Hp = H @ p
        r = r - alpha * Hp

        erro = np.linalg.norm(r)

        if erro < tol:
            break

        norm_r_new_sq = float(r @ r)

        if norm_r_sq == 0.0:
            break

        beta = norm_r_new_sq / norm_r_sq

        p = H.T @ r + beta * p

    fim = time.time()

    return {
        "f": f,
        "tempo": fim - inicio,
        "iteracoes": iteracoes,
        "erro": erro,
        "erro_abs": abs(erro),
        "lambda": lamb
    }

def salvar_imagem(f, resolucao, nome_arquivo):
    f = np.asarray(f, dtype=np.float64).reshape(-1)

    largura = resolucao
    altura = resolucao

    f_min = float(f.min())
    f_max = float(f.max())
    f_range = f_max - f_min if f_max != f_min else 1.0

    x_idx = np.arange(largura, dtype=np.int32).reshape(1, -1)
    y_idx = np.arange(altura, dtype=np.int32).reshape(-1, 1)

    indices = x_idx * altura + y_idx

    img = ((f[indices] - f_min) / f_range * 255.0)
    img = img.clip(0, 255).astype(np.uint8)

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

def executar_algoritmo_aleatorio(H, g):
    algoritmo = random.choice(["CGNR", "CGNE"])

    if algoritmo == "CGNR":
        resultado = cgnr(H, g)
    else:
        resultado = cgne(H, g)

    resultado["algoritmo"] = algoritmo

    return resultado

if __name__ == "__main__":
    resolucao_desejada = 60
    algoritmo = "CGNR"

    print("Carregando H e g...")

    if resolucao_desejada == 60:
        H = carregar_matriz_esparsa("sinais/H-1.csv")
        g = carregar_csv("sinais/G-1.csv")
    elif resolucao_desejada == 30:
        H = carregar_matriz_esparsa("sinais/H-2.csv")
        g = carregar_csv("sinais/G-1.csv")
    else:
        raise ValueError("Resolução inválida. Use 30 ou 60.")

    resolucao = descobrir_resolucao(H)

    print("Resolução:", f"{resolucao}x{resolucao}")
    print("H:", H.shape)
    print("g:", g.shape)

    print(f"Rodando {algoritmo}...")

    inicio_total = time.perf_counter()

    if algoritmo == "CGNR":
        resultado = cgnr(H, g)
    elif algoritmo == "CGNE":
        resultado = cgne(H, g)
    else:
        raise ValueError("Algoritmo inválido. Use CGNR ou CGNE.")

    fim_total = time.perf_counter()

    f = resultado["f"]

    nome_imagem = f"imagem_reconstruida_{resolucao}x{resolucao}_{algoritmo}.png"

    salvar_imagem(f, resolucao, nome_imagem)

    print("Tempo algoritmo:", resultado["tempo"])
    print("Tempo total:", fim_total - inicio_total)
    print("Iterações:", resultado["iteracoes"])
    print("Erro:", resultado["erro"])
    print("Lambda:", resultado["lambda"])
    print("Imagem salva em:", nome_imagem)
    print("Finalizado.")