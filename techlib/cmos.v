// CMOS transistor-level implementations of the Yosys gate primitives.
//
// Used with:  yosys -p "read_verilog -sv Aegis.sv; synth -top <m>; techmap -map cmos.v"
//
// Transistor primitive port order in Yosys (Verilog `nmos`/`pmos`):
//     nmos/pmos (drain, source, gate)          -> JSON ports $1, $2, $3
// VDD is 1'b1 and GND is 1'b0.
//
// Combinational cells are exact CMOS networks; the register cells are the
// standard master-slave transmission-gate flip-flop plus the enable/reset
// steering muxes (a structural expansion, not a timing-closed library cell).

module \$_NOT_ (A, Y);
  input A;
  output Y;
  pmos pu (Y, 1'b1, A);
  nmos pd (Y, 1'b0, A);
endmodule

module \$_NAND_ (A, B, Y);
  input A, B;
  output Y;
  wire m;
  pmos p1 (Y, 1'b1, A);
  pmos p2 (Y, 1'b1, B);
  nmos n1 (Y, m, A);
  nmos n2 (m, 1'b0, B);
endmodule

module \$_AND_ (A, B, Y);
  input A, B;
  output Y;
  wire n;
  \$_NAND_ g (.A(A), .B(B), .Y(n));
  \$_NOT_ i (.A(n), .Y(Y));
endmodule

module \$_NOR_ (A, B, Y);
  input A, B;
  output Y;
  wire m;
  pmos p1 (Y, m, A);
  pmos p2 (m, 1'b1, B);
  nmos n1 (Y, 1'b0, A);
  nmos n2 (Y, 1'b0, B);
endmodule

module \$_OR_ (A, B, Y);
  input A, B;
  output Y;
  wire n;
  \$_NOR_ g (.A(A), .B(B), .Y(n));
  \$_NOT_ i (.A(n), .Y(Y));
endmodule

module \$_ANDNOT_ (A, B, Y);
  input A, B;
  output Y;
  wire nb, n;
  \$_NOT_ i0 (.A(B), .Y(nb));
  \$_NAND_ g (.A(A), .B(nb), .Y(n));
  \$_NOT_ i1 (.A(n), .Y(Y));
endmodule

module \$_ORNOT_ (A, B, Y);
  input A, B;
  output Y;
  wire na;
  \$_NOT_ i0 (.A(A), .Y(na));
  \$_NAND_ g (.A(na), .B(B), .Y(Y));
endmodule

module \$_XOR_ (A, B, Y);
  input A, B;
  output Y;
  wire na, nb, t1, t2;
  \$_NOT_ i0 (.A(A), .Y(na));
  \$_NOT_ i1 (.A(B), .Y(nb));
  \$_AND_ a0 (.A(A), .B(nb), .Y(t1));
  \$_AND_ a1 (.A(na), .B(B), .Y(t2));
  \$_OR_  o0 (.A(t1), .B(t2), .Y(Y));
endmodule

module \$_XNOR_ (A, B, Y);
  input A, B;
  output Y;
  wire x;
  \$_XOR_ x0 (.A(A), .B(B), .Y(x));
  \$_NOT_ i0 (.A(x), .Y(Y));
endmodule

module \$_MUX_ (A, B, S, Y);
  input A, B, S;
  output Y;
  wire nS;
  \$_NOT_ inv (.A(S), .Y(nS));
  pmos pA (Y, A, S);
  nmos nA (Y, A, nS);
  pmos pB (Y, B, nS);
  nmos nB (Y, B, S);
endmodule

// DFF with active-high enable (no reset).
module \$_DFFE_PP_ (C, D, E, Q);
  input C, D, E;
  output Q;
  wire hold;
  \$_MUX_ m0 (.A(Q), .B(D), .S(E), .Y(hold));
  \$_DFF_P_ ff (.C(C), .D(hold), .Q(Q));
endmodule

// Positive-edge D flip-flop: transmission-gate master-slave latch pair.
module \$_DFF_P_ (C, D, Q);
  input C, D;
  output Q;
  wire nC, m, mbar, s;
  \$_NOT_ ic (.A(C), .Y(nC));
  // master transparent when C = 0
  nmos m1 (m, D, nC);
  pmos m2 (m, D, C);
  \$_NOT_ im (.A(m), .Y(mbar));
  // master hold when C = 1
  nmos m3 (m, mbar, C);
  pmos m4 (m, mbar, nC);
  // slave transparent when C = 1
  nmos s1 (s, m, C);
  pmos s2 (s, m, nC);
  \$_NOT_ is (.A(s), .Y(Q));
  // slave hold when C = 0
  nmos s3 (s, Q, nC);
  pmos s4 (s, Q, C);
endmodule

// DFF with active-high enable and synchronous reset-to-0.
module \$_SDFFE_PP0P_ (C, D, E, R, Q);
  input C, D, E, R;
  output Q;
  wire hold, nxt;
  \$_MUX_ m0 (.A(Q), .B(D), .S(E), .Y(hold));
  \$_MUX_ m1 (.A(hold), .B(1'b0), .S(R), .Y(nxt));
  \$_DFF_P_ ff (.C(C), .D(nxt), .Q(Q));
endmodule

// DFF with active-low enable and synchronous reset-to-0.
module \$_SDFFE_PP0N_ (C, D, E, R, Q);
  input C, D, E, R;
  output Q;
  wire hold, nxt;
  \$_MUX_ m0 (.A(D), .B(Q), .S(E), .Y(hold));
  \$_MUX_ m1 (.A(hold), .B(1'b0), .S(R), .Y(nxt));
  \$_DFF_P_ ff (.C(C), .D(nxt), .Q(Q));
endmodule

// DFF with synchronous reset-to-0 (no enable).
module \$_SDFF_PP0_ (C, D, R, Q);
  input C, D, R;
  output Q;
  wire nxt;
  \$_MUX_ m0 (.A(D), .B(1'b0), .S(R), .Y(nxt));
  \$_DFF_P_ ff (.C(C), .D(nxt), .Q(Q));
endmodule

// Async-reset DFF: modelled structurally as reset-mux + DFF (no async path in
// the transistor library).
module \$_DFF_PN0_ (C, D, R, Q);
  input C, D, R;
  output Q;
  wire nxt;
  \$_MUX_ m0 (.A(D), .B(1'b0), .S(R), .Y(nxt));
  \$_DFF_P_ ff (.C(C), .D(nxt), .Q(Q));
endmodule
