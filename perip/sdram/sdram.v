module sdram(
  input        clk,
  input        cke,
  input        cs,
  input        ras,
  input        cas,
  input        we,
  input [12:0] a,
  input [ 1:0] ba,
  input [ 1:0] dqm,
  inout [15:0] dq
);

	//inst
	localparam INST_NOP       = 3'b111;
	localparam INST_ACTIVE    = 3'b011;
	localparam INST_READ      = 3'b101;
	localparam INST_WRITE     = 3'b100;
	localparam INST_TERMINATE = 3'b110;
	localparam INST_PRECHARGE = 3'b010;
	localparam INST_REFRESH   = 3'b001;
	localparam INST_MODE_REG  = 3'b000;

	wire [15:0] dq_in;
	wire [15:0] dq_out;
	wire [2:0]  inst;
	wire        dq_outen;
	wire [2:0]  cas_latency;
	wire [2:0]  data_index;
	reg  [3:0]  burst_length;
	reg  [15:0] memory [3:0][8191:0][511:0];
	reg  [12:0] active_row [3:0];
	reg  [12:0] mode_reg;
	reg  [8:0]  addr_col;
	reg  [3:0]  read_remain_burst;
	reg  [3:0]  write_remain_burst;
	reg  [15:0] data_queue [1:0];
	reg  [1:0]  dqm_reg;
	reg  [1:0]  ba_reg;

	assign data_index = (cas_latency == 3'd3) ? 3'd1 : 3'd0;
	assign dq_outen = |read_remain_burst;
	assign dq_out = data_queue[data_index[0]];
	assign cas_latency = mode_reg[6:4];
	assign inst = {ras, cas, we};
	assign dq_in = dq;
  assign dq = dq_outen ? dq_out : 16'bz;

	always @(*) begin
		case (mode_reg[2:0])
			3'b000: burst_length = 4'b0001;
			3'b001: burst_length = 4'b0010;
			3'b010: burst_length = 4'b0100;
			3'b011: burst_length = 4'b1000;
			default: burst_length = 4'b0;
		endcase
	end

	//mode_reg
	always @(posedge clk) begin
		if (~cs & (inst == INST_MODE_REG)) mode_reg <= a;
		else mode_reg <= mode_reg;
	end

	//active_row
	always @(posedge clk) begin
		if (~cs & (inst == INST_ACTIVE)) begin
			active_row[ba] <= a;
		end
		else active_row <= active_row;
	end

	//addr_col
	always @(posedge clk) begin
		if (~cs & ((inst == INST_READ) | (inst == INST_WRITE))) addr_col <= a[8:0];
		else if (cs | (inst == INST_NOP)) addr_col <= addr_col + 1;
		else addr_col <= 9'b0;
	end

	//data_queue
	always @(posedge clk) begin
		if (~cs & (|read_remain_burst)) begin
			data_queue[1] <= data_queue[0];
			data_queue[0] <= memory[ba_reg][active_row[ba_reg]][addr_col];
		end
		else if (~cs & ((|write_remain_burst) | (inst == INST_WRITE))) begin
			data_queue[0] <= dq_in;
		end
	end

	//dqm_reg
	always @(posedge clk) begin
		if (~cs & (inst == INST_WRITE) | (|write_remain_burst)) dqm_reg <= dqm;
	end

	//ba_reg
	always @(posedge clk) begin
		if (~cs & ((inst == INST_READ) | (inst == INST_WRITE))) ba_reg <= ba;
	end

	//memory
	always @(posedge clk) begin
		if (|write_remain_burst) begin
			if (~dqm_reg[0]) memory[ba_reg][active_row[ba_reg]][addr_col][7:0] <= data_queue[0][7:0];
			if (~dqm_reg[1]) memory[ba_reg][active_row[ba_reg]][addr_col][15:8] <= data_queue[0][15:8];
		end
	end

	//write_remain_burst
	always @(posedge clk) begin
		if (~cs & (inst == INST_WRITE)) write_remain_burst <= burst_length;
		else if (~cs & (inst == INST_TERMINATE) & (write_remain_burst > 0)) write_remain_burst <= 4'b0;
		else if (write_remain_burst > 0) write_remain_burst <= write_remain_burst - 1;
		else write_remain_burst <= 0;
	end

	//read_remain_burst
	always @(posedge clk) begin
		if (~cs & (inst == INST_READ)) read_remain_burst <= burst_length + {1'b0, cas_latency} - 1;
		else if (~cs & (inst == INST_TERMINATE) & (read_remain_burst > 1)) read_remain_burst <= 4'b1;
		else if (read_remain_burst > 0) read_remain_burst <= read_remain_burst - 1;
		else read_remain_burst <= 0;
	end

endmodule
