# Source material: the citric acid cycle (Krebs cycle)

**Provenance note:** this document is original content written for this
project to exercise the AI card-generation pipeline end to end. It is not
copied from any textbook, MCAT prep company, or other copyrighted source —
that matters because the pipeline needs a source it's legally clear to
chunk, embed, generate from, and reproduce excerpts of without any
copyright question. The facts themselves are standard biochemistry
(anyone can verify them against any textbook); the wording and structure
here are original. Every card the generator produces must cite a chunk ID
from this document (`kc-01` through `kc-14`), which is what "traces to a
named source" means in practice for this pipeline.

## kc-01: Overview and purpose

The citric acid cycle, also called the Krebs cycle or the tricarboxylic
acid (TCA) cycle, is the second major stage of cellular respiration,
following glycolysis. Its job is to finish oxidizing the carbon that
entered as glucose, harvesting high-energy electrons along the way. Those
electrons are captured by NAD+ and FAD, producing NADH and FADH2, which
then feed the electron transport chain. The cycle itself makes only a
small amount of ATP directly; its real output is the reduced electron
carriers that power oxidative phosphorylation downstream.

## kc-02: Location

The cycle runs in the mitochondrial matrix, the innermost compartment of
the mitochondrion, enclosed by the inner mitochondrial membrane. This
matters mechanistically: the enzymes of the cycle are dissolved in the
matrix (except succinate dehydrogenase, which is embedded in the inner
membrane itself), and the inner membrane is where the electron transport
chain sits immediately downstream, so NADH and FADH2 produced in the
matrix don't have far to travel.

## kc-03: Entry point - acetyl-CoA formation

Pyruvate produced by glycolysis in the cytoplasm is transported into the
mitochondrial matrix, where the pyruvate dehydrogenase complex converts
each pyruvate into acetyl-CoA, releasing one CO2 and reducing one NAD+ to
NADH in the process. This step, sometimes called the "link reaction," is
not technically part of the citric acid cycle itself, but it's the
required entry point: acetyl-CoA is what actually feeds into the cycle's
first step.

## kc-04: Step 1 - citrate synthase

Acetyl-CoA (2 carbons) combines with oxaloacetate (4 carbons), a molecule
left over from the end of the previous turn of the cycle, to form citrate
(6 carbons). This reaction is catalyzed by citrate synthase and releases
the CoA group. Citrate synthase is one of the cycle's key regulatory
enzymes: it's inhibited when ATP, NADH, and citrate itself are abundant,
signaling that the cell already has enough energy on hand.

## kc-05: Steps 2-3 - isomerization to isocitrate

Citrate is isomerized to isocitrate through an intermediate called
cis-aconitate, both steps catalyzed by the enzyme aconitase. This is a
structural rearrangement only - no carbons are gained or lost, and no
electron carriers are produced. The point of the rearrangement is to move
a hydroxyl group into a position where it can be oxidized in the next
step.

## kc-06: Step 4 - isocitrate dehydrogenase

Isocitrate is oxidized and loses a carbon as CO2, becoming
alpha-ketoglutarate (5 carbons). This is the cycle's first
carbon-releasing, energy-yielding step: it reduces one NAD+ to NADH.
Isocitrate dehydrogenase is generally considered the rate-limiting enzyme
of the cycle and is allosterically activated by ADP and calcium, and
inhibited by ATP and NADH - it's the cycle's main throttle.

## kc-07: Step 5 - alpha-ketoglutarate dehydrogenase

Alpha-ketoglutarate (5 carbons) is oxidized and loses a second carbon as
CO2, becoming succinyl-CoA (4 carbons, attached to CoA). A second NADH is
produced here. This enzyme complex is structurally and mechanistically
very similar to pyruvate dehydrogenase from the link reaction, and both
require the same set of cofactors (thiamine pyrophosphate, lipoic acid,
FAD, NAD+, and CoA).

## kc-08: Step 6 - succinyl-CoA synthetase

Succinyl-CoA is converted to succinate, and the energy released from
breaking the high-energy thioester bond to CoA is captured directly as
GTP (or ATP in some tissues), via substrate-level phosphorylation - the
only step in the cycle that produces a nucleoside triphosphate directly,
without going through the electron transport chain.

## kc-09: Step 7 - succinate dehydrogenase

Succinate is oxidized to fumarate, reducing FAD directly to FADH2 (not
NAD+ to NADH - this is the cycle's only FADH2-producing step). Succinate
dehydrogenase is embedded in the inner mitochondrial membrane and is also
Complex II of the electron transport chain, making it the one enzyme that
is physically part of both the citric acid cycle and the electron
transport chain.

## kc-10: Step 8 - malate dehydrogenase and regeneration

Fumarate is hydrated to malate (by fumarase), and then malate is oxidized
to regenerate oxaloacetate, producing the cycle's fourth and final NADH.
Oxaloacetate is now back where the cycle started, ready to combine with a
new acetyl-CoA and run again. This regeneration is why it's called a
cycle rather than a linear pathway.

## kc-11: Net yield per turn

Each single turn of the citric acid cycle, starting from one acetyl-CoA,
produces: 3 NADH, 1 FADH2, 1 GTP (or ATP), and releases 2 CO2. Because
each glucose molecule yields two pyruvate (and therefore two acetyl-CoA),
a full glucose molecule drives the cycle twice, doubling all of these
numbers before they're counted toward total ATP yield from oxidative
phosphorylation.

## kc-12: Regulation

The cycle is regulated primarily at three enzymes: citrate synthase,
isocitrate dehydrogenase, and alpha-ketoglutarate dehydrogenase. All
three are inhibited by high ATP and NADH (signs the cell has enough
energy) and activated by high ADP or NAD+ (signs the cell needs more).
This is classic feedback regulation: the products of the pathway
(reduced electron carriers, ultimately ATP) suppress the pathway that
made them once enough has accumulated.

## kc-13: The cycle is amphibolic

The citric acid cycle is amphibolic, meaning it runs both catabolically
(breaking down fuel for energy) and anabolically (supplying building
blocks for biosynthesis). Intermediates like alpha-ketoglutarate and
oxaloacetate are precursors for amino acid synthesis, succinyl-CoA feeds
into heme synthesis, and citrate can be exported to the cytoplasm as a
source of acetyl-CoA for fatty acid synthesis. Pulling intermediates out
for these purposes requires anaplerotic reactions to replenish them, or
the cycle would stall.

## kc-14: MCAT relevance and common exam traps

A frequent MCAT trap is assuming the cycle directly produces a large
amount of ATP - it doesn't; most of the energy payoff comes later, when
NADH and FADH2 are used by the electron transport chain. Another common
trap is forgetting that the cycle turns twice per glucose, not once,
which trips up net-yield calculations. Students should also be able to
identify which steps release CO2 (kc-06 and kc-07), which step is
substrate-level phosphorylation (kc-08), and which enzyme is shared with
the electron transport chain (kc-09, succinate dehydrogenase / Complex
II).
