package com.naoufel.decentralchess.chess

import java.security.MessageDigest

enum class Side { WHITE, BLACK }
enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
data class Piece(val side: Side, val type: PieceType)
data class Square(val file: Int, val rank: Int) { val index get() = rank * 8 + file }
data class Move(val from: Square, val to: Square, val promotion: PieceType? = null)

data class CastlingRights(val whiteKingSide:Boolean=true,val whiteQueenSide:Boolean=true,val blackKingSide:Boolean=true,val blackQueenSide:Boolean=true){
    override fun toString()=buildString{if(whiteKingSide)append('K');if(whiteQueenSide)append('Q');if(blackKingSide)append('k');if(blackQueenSide)append('q');if(isEmpty())append('-')}
}

data class Position(val board:Array<Piece?>,val sideToMove:Side,val moveNumber:Int=1,val castling:CastlingRights=CastlingRights(),val enPassant:Square?=null,val halfmoveClock:Int=0){
    fun pieceAt(s:Square):Piece?=if(s.file in 0..7&&s.rank in 0..7)board[s.index] else null
    fun isSquareAttacked(s:Square,by:Side):Boolean{
        val pr=s.rank+if(by==Side.WHITE)-1 else 1
        for(df in intArrayOf(-1,1))if(pr in 0..7&&s.file+df in 0..7&&pieceAt(Square(s.file+df,pr))==Piece(by,PieceType.PAWN))return true
        val nf=intArrayOf(1,2,2,1,-1,-2,-2,-1);val nr=intArrayOf(2,1,-1,-2,-2,-1,1,2)
        for(i in 0..7)if(pieceAt(Square(s.file+nf[i],s.rank+nr[i]))==Piece(by,PieceType.KNIGHT))return true
        for(df in -1..1)for(dr in -1..1)if((df!=0||dr!=0)&&pieceAt(Square(s.file+df,s.rank+dr))==Piece(by,PieceType.KING))return true
        if(rayAttacked(s,by,intArrayOf(1,-1,1,-1),intArrayOf(1,1,-1,-1),setOf(PieceType.BISHOP,PieceType.QUEEN)))return true
        return rayAttacked(s,by,intArrayOf(1,-1,0,0),intArrayOf(0,0,1,-1),setOf(PieceType.ROOK,PieceType.QUEEN))
    }
    private fun rayAttacked(s:Square,by:Side,dfs:IntArray,drs:IntArray,types:Set<PieceType>):Boolean{for(i in dfs.indices){var f=s.file+dfs[i];var r=s.rank+drs[i];while(f in 0..7&&r in 0..7){val p=pieceAt(Square(f,r));if(p!=null){if(p.side==by&&p.type in types)return true;break};f+=dfs[i];r+=drs[i]}};return false}
    fun kingSquare(side:Side):Square?=board.indices.firstOrNull{board[it]==Piece(side,PieceType.KING)}?.let{Square(it%8,it/8)}
    fun inCheck(side:Side):Boolean=kingSquare(side)?.let{isSquareAttacked(it,if(side==Side.WHITE)Side.BLACK else Side.WHITE)}?:true

    fun pseudoLegalMoves(from:Square):List<Move>{
        val p=pieceAt(from)?:return emptyList();if(p.side!=sideToMove)return emptyList();val out=mutableListOf<Move>()
        fun add(f:Int,r:Int,promotion:Boolean=false){if(f !in 0..7||r !in 0..7)return;val t=pieceAt(Square(f,r));if(t?.side==p.side||t?.type==PieceType.KING)return;if(promotion)listOf(PieceType.QUEEN,PieceType.ROOK,PieceType.BISHOP,PieceType.KNIGHT).forEach{out+=Move(from,Square(f,r),it)}else out+=Move(from,Square(f,r))}
        when(p.type){
            PieceType.KNIGHT->for(d in arrayOf(1 to 2,2 to 1,2 to -1,1 to -2,-1 to -2,-2 to -1,-2 to 1,-1 to 2))add(from.file+d.first,from.rank+d.second)
            PieceType.KING->{for(df in -1..1)for(dr in -1..1)if(df!=0||dr!=0)add(from.file+df,from.rank+dr);val enemy=if(p.side==Side.WHITE)Side.BLACK else Side.WHITE
                val rank=from.rank;val kingHome=if(p.side==Side.WHITE)0 else 7
                if(from.rank==kingHome&&!inCheck(p.side)){
                    if((p.side==Side.WHITE&&castling.whiteKingSide||p.side==Side.BLACK&&castling.blackKingSide)&&pieceAt(Square(7,rank))==Piece(p.side,PieceType.ROOK)&&pieceAt(Square(5,rank))==null&&pieceAt(Square(6,rank))==null&&!isSquareAttacked(Square(5,rank),enemy)&&!isSquareAttacked(Square(6,rank),enemy))out+=Move(from,Square(6,rank))
                    if((p.side==Side.WHITE&&castling.whiteQueenSide||p.side==Side.BLACK&&castling.blackQueenSide)&&pieceAt(Square(0,rank))==Piece(p.side,PieceType.ROOK)&&pieceAt(Square(1,rank))==null&&pieceAt(Square(2,rank))==null&&pieceAt(Square(3,rank))==null&&!isSquareAttacked(Square(3,rank),enemy)&&!isSquareAttacked(Square(2,rank),enemy))out+=Move(from,Square(2,rank))
                }
            }
            PieceType.BISHOP,PieceType.ROOK,PieceType.QUEEN->{val dirs=when(p.type){PieceType.BISHOP->arrayOf(1 to 1,1 to -1,-1 to 1,-1 to -1);PieceType.ROOK->arrayOf(1 to 0,-1 to 0,0 to 1,0 to -1);else->arrayOf(1 to 1,1 to -1,-1 to 1,-1 to -1,1 to 0,-1 to 0,0 to 1,0 to -1)};for((df,dr)in dirs){var f=from.file+df;var r=from.rank+dr;while(f in 0..7&&r in 0..7){val t=pieceAt(Square(f,r));if(t==null)out+=Move(from,Square(f,r))else{if(t.side!=p.side&&t.type!=PieceType.KING)out+=Move(from,Square(f,r));break};f+=df;r+=dr}}}
            PieceType.PAWN->{val dir=if(p.side==Side.WHITE)1 else -1;val start=if(p.side==Side.WHITE)1 else 6;val promo=if(p.side==Side.WHITE)7 else 0;val one=Square(from.file,from.rank+dir);if(one.rank in 0..7&&pieceAt(one)==null){add(one.file,one.rank,one.rank==promo);if(from.rank==start&&pieceAt(Square(from.file,from.rank+2*dir))==null)out+=Move(from,Square(from.file,from.rank+2*dir))};for(df in intArrayOf(-1,1)){val to=Square(from.file+df,from.rank+dir);if(to.file in 0..7&&to.rank in 0..7){val t=pieceAt(to);if(t!=null&&t.side!=p.side&&t.type!=PieceType.KING)add(to.file,to.rank,to.rank==promo)else if(t==null&&enPassant==to)out+=Move(from,to)}}}
        };return out
    }
    fun legalMoves(from:Square?=null):List<Move>{val sources=if(from!=null)listOf(from)else board.indices.map{Square(it%8,it/8)};return sources.flatMap{pseudoLegalMoves(it)}.filter{!applyUnchecked(it).inCheck(sideToMove)}}
    fun apply(move:Move):Position{require(move in legalMoves()){"Illegal move: $move"};return applyUnchecked(move)}

    private fun applyUnchecked(move:Move):Position{
        val next=board.copyOf();val moving=next[move.from.index]?:error("No piece on source square");val captured=next[move.to.index];next[move.from.index]=null
        val ep=if(moving.type==PieceType.PAWN&&kotlin.math.abs(move.to.rank-move.from.rank)==2)Square(move.from.file,(move.from.rank+move.to.rank)/2)else null
        if(moving.type==PieceType.PAWN&&move.to==enPassant&&captured==null){val capturedPawnRank=move.to.rank+if(moving.side==Side.WHITE)-1 else 1;next[Square(move.to.file,capturedPawnRank).index]=null}
        next[move.to.index]=if(move.promotion!=null)Piece(moving.side,move.promotion)else moving
        if(moving.type==PieceType.KING&&kotlin.math.abs(move.to.file-move.from.file)==2){val rank=move.from.rank;val rf=if(move.to.file==6)Square(7,rank)else Square(0,rank);val rt=if(move.to.file==6)Square(5,rank)else Square(3,rank);next[rt.index]=next[rf.index];next[rf.index]=null}
        var cr=castling
        if(moving.type==PieceType.KING)cr=if(moving.side==Side.WHITE)cr.copy(whiteKingSide=false,whiteQueenSide=false)else cr.copy(blackKingSide=false,blackQueenSide=false)
        if(moving.type==PieceType.ROOK)cr=when(move.from.index){0->cr.copy(whiteQueenSide=false);7->cr.copy(whiteKingSide=false);56->cr.copy(blackQueenSide=false);63->cr.copy(blackKingSide=false);else->cr}
        if(captured?.type==PieceType.ROOK)cr=when(move.to.index){0->cr.copy(whiteQueenSide=false);7->cr.copy(whiteKingSide=false);56->cr.copy(blackQueenSide=false);63->cr.copy(blackKingSide=false);else->cr}
        val half=if(moving.type==PieceType.PAWN||captured!=null||move.to==enPassant)0 else halfmoveClock+1
        return Position(next,if(sideToMove==Side.WHITE)Side.BLACK else Side.WHITE,if(sideToMove==Side.BLACK)moveNumber+1 else moveNumber,cr,ep,half)
    }

    fun toFen():String{val ranks=(7 downTo 0).joinToString("/"){r->var e=0;buildString{for(f in 0..7){val p=pieceAt(Square(f,r));if(p==null)e++else{if(e>0){append(e);e=0};append(p.fenChar())}};if(e>0)append(e)}};val ep=enPassant?.let{"${('a'.code+it.file).toChar()}${it.rank+1}"}?:"-";return "$ranks ${if(sideToMove==Side.WHITE)"w" else "b"} $castling $ep $halfmoveClock $moveNumber"}
    fun stableHash():String=sha256(toFen())
    companion object{
        fun initial():Position{val b=arrayOfNulls<Piece>(64);fun put(r:Int,f:Int,s:Side,t:PieceType){b[r*8+f]=Piece(s,t)};val back=listOf(PieceType.ROOK,PieceType.KNIGHT,PieceType.BISHOP,PieceType.QUEEN,PieceType.KING,PieceType.BISHOP,PieceType.KNIGHT,PieceType.ROOK);for(f in 0..7){put(0,f,Side.WHITE,back[f]);put(1,f,Side.WHITE,PieceType.PAWN);put(6,f,Side.BLACK,PieceType.PAWN);put(7,f,Side.BLACK,back[f])};return Position(b,Side.WHITE)}
        fun fromFen(fen:String):Position{val p=fen.trim().split(Regex("\\s+"));require(p.size>=4);val b=arrayOfNulls<Piece>(64);var rank=7;var file=0;for(c in p[0])when{c=='/'->{rank--;file=0};c.isDigit()->file+=c-'0';else->{b[rank*8+file]=Piece(if(c.isUpperCase())Side.WHITE else Side.BLACK,PieceType.values().first{it.fenChar().equals(c.uppercaseChar().toString())});file++}};val side=if(p[1]=="w")Side.WHITE else Side.BLACK;val rights=CastlingRights(p[2].contains('K'),p[2].contains('Q'),p[2].contains('k'),p[2].contains('q'));val ep=if(p[3]=="-")null else Square(p[3][0]-'a',p[3][1]-'1');return Position(b,side,p.getOrNull(5)?.toIntOrNull()?:1,rights,ep,p.getOrNull(4)?.toIntOrNull()?:0)}
        private fun sha256(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
private fun Piece.fenChar():String=when(type){PieceType.KING->"k";PieceType.QUEEN->"q";PieceType.ROOK->"r";PieceType.BISHOP->"b";PieceType.KNIGHT->"n";PieceType.PAWN->"p"}.let{if(side==Side.WHITE)it.uppercase()else it}
