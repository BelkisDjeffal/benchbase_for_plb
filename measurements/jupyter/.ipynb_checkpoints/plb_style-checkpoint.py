import matplotlib.pyplot as plt

# Path to your style file
PLB_STYLE_PATH = "/home/spirals/plb_theme.mplstyle"

def apply_plb_style():
    plt.style.use(PLB_STYLE_PATH)

# Main strategy colors
STRATEGY_COLORS = {
    "PLB":       "#1696A3",  # main teal
    "5PLB10":    "#1696A3",  # PLB strategy name in your data

    "3RR+2RR":   "#C44E52",
    "4RR+1RR":   "#D8A031",

    # PLB buckets:
    "PLB_P<F":   "#73C6CF",
    "PLB_P=F":   "#3BAFB9",
    "PLB_P>F":   "#0F6F77",
}

# Seaborn-style palette if you use hue="Strategy"
STRATEGY_PALETTE = {
    "5PLB10":    "#1696A3",
    "3RR+2RR":   "#C44E52",
    "4RR+1RR":   "#D8A031",
    "PLB_P<F":   "#73C6CF",
    "PLB_P=F":   "#3BAFB9",
    "PLB_P>F":   "#0F6F77",
}
