package dancingBallsPKG;

public abstract class myForce {
	protected static DancingBalls pa;
	public static int ID_gen = 0;
	public int ID;
	public String name;
	public double constVal1;				//multiplicative constant to be applied to mass to find force/ ks
	public double constVal2;
	public myVectorf constVec;				//vector constant quantity, for use with gravity
	public ForceType ftype;

	public myForce(DancingBalls _p, String _n, double _k1, double _k2, myVectorf _constVec, ForceType _t){
  		pa = _p; 
		ID = ++ID_gen;
		name = new String(_n);
		constVal1 = _k1; 
		constVal2 = _k2;
		constVec = _constVec;		//torque-result force
		ftype = _t;
	}
	public myForce(DancingBalls _p,String _n, double _k1, double _k2) {this(_p, _n, _k1, _k2, new myVectorf(), ForceType.DAMPSPRING);}
	public myForce(DancingBalls _p,String _n, double _k) {this(_p, _n, _k * (_k>0 ? 1 : -1), 0, new myVectorf(), (_k>0) ? ForceType.REPL : ForceType.ATTR); ID = -1;}
	
	public void setConstVal1(double _c) {constVal1 = _c;}
	public void setConstVal2(double _c) {constVal2 = _c;}	
	
	public abstract myVectorf[] calcForceOnParticle(myParticle _p1, myParticle _p2, double d);// {S_SCALAR,S_VECTOR, ATTR, SPRING};
	@Override
	public String toString(){return "Force Name : " + name + " ID : " + ID + " Type : " + pa.ForceType2str[ftype.getVal()];}
}//myForce class

class mySclrForce extends myForce{
	// "scalar" force here means we derive the force by a particle-dependent scalar value, in this case mass against gravity vec 
	public mySclrForce(DancingBalls _p,String _n, myVectorf _G) { super(_p,_n, 0 ,0, new myVectorf(_G), ForceType.S_SCALAR);}	//	

	@Override
	//array returns up to 2 forces, one on p1, one on p2
	public myVectorf[] calcForceOnParticle(myParticle _p1, myParticle _p2, double d) {
		myVectorf[] result = new myVectorf[]{new myVectorf(),new myVectorf()};
		result[0] = myVectorf._mult(constVec,_p1.mass);
		return result;
	}
	@Override
	public String toString(){return super.toString() + "\tForce Vector :  " + constVec.toString();}
	
}//mySclrForce - scalar body-specific multiple of vector force

class myVecForce extends myForce{
	//vector here means we derive the force as a particle-dependent vector value, like velocity, against some scalar kd
	public myVecForce(DancingBalls _p,String _n, double _k) { super(_p,_n,_k,0, new myVectorf(), ForceType.S_VECTOR);}		//if drag, needs to be negative constant value	

	@Override
	public myVectorf[] calcForceOnParticle(myParticle _p1, myParticle _p2, double d) {
		myVectorf[] result = new myVectorf[]{new myVectorf(),new myVectorf()};
		result[0] = myVectorf._mult(_p1.aVelocity[_p1.curIDX], constVal1);//vector here means we derive the force as a particle-dependent vector value, velocity, against some scalar kd 
		return result;
	}
	@Override
	public String toString(){return super.toString() + "\tForce Scaling Constant :  " + String.format("%.4f",constVal1);}
	
}//myVecForce - vector body-specific quantity multiplied by scalar constant

class my2bdyForce extends myForce{
	//attractive/repulsive force
	public my2bdyForce(DancingBalls _p,  String _n, double _k,  ForceType _t) {
		super(_p, _n, _k, 0, new myVectorf(), _t);
	}
	public my2bdyForce(DancingBalls _p, String _n, double _k) {//passed k > 0 is repulsive force, k < 0 is attractive force
		this(_p, _n, Math.abs(_k), (_k>0) ? ForceType.REPL : ForceType.ATTR);
	}
	@Override
	public myVectorf[] calcForceOnParticle(myParticle _p1, myParticle _p2, double d) {
		myVectorf[] result = new myVectorf[]{new myVectorf(),new myVectorf()};
		myVectorf vecL;
		vecL = new myVectorf(_p2.aPosition[_p2.curIDX],_p1.aPosition[_p1.curIDX]);//vector from 2 to 1
		if (vecL.magn > pa.epsValCalc) {		
			double m1 = _p1.mass, m2 = _p2.mass;
			myVectorf lnorm = myVectorf._normalize(vecL);			//unitlength vector of l
			double fp = constVal1 * m1 * m2 / (vecL.sqMagn);		//from 2 to 1 if constVal > 0 (repulsive force)
			result[0] = myVectorf._mult(lnorm, fp);				//force applied to p1
			result[1] = myVectorf._mult(lnorm, -fp);				//force applied to p2
		}//only add force if magnitude of distance vector is not 0
		return result;
	}	
	@Override
	public String toString(){return super.toString() + "\tForce Scaling Constant :  " + String.format("%.4f",constVal1);}	
}

//spring force to rest position
class mySpringToRest extends myForce{
	//damped spring
	public mySpringToRest(DancingBalls _p,  String _n, double _k, double _k2,  ForceType _t) {
		super(_p, _n, _k, _k2, new myVectorf(), _t);
	}
	public mySpringToRest(DancingBalls _p, String _n, double _k,double _k2) {//passed k > 0 is repulsive force, k < 0 is attractive force
		this(_p, _n, _k, _k2, ForceType.DAMPSPRING);
	}
	
	//_p2 should be null, d should be 0, since we have it hardcoded to be to initPos
	@Override
	public myVectorf[] calcForceOnParticle(myParticle _p1, myParticle _p2, double d) {
		myVectorf[] result = new myVectorf[]{new myVectorf(),new myVectorf()};
		myVectorf vecL;
		vecL = new myVectorf(_p1.initPos,_p1.aPosition[_p1.curIDX]);//vector from current position to init position
		if (vecL.magn > pa.epsValCalc) {		
			myVectorf lnorm = myVectorf._normalize(vecL);//unitlength vector of l
			myVectorf lprime = myVectorf._sub(vecL, _p1.vecLOld);		//lprime - time derivative of length, subtract old length vector from new length vector ?
			double KsTerm = constVal1 * (vecL.magn - d);
			double KdTerm = constVal2 * (lprime._dot(lnorm));//was _dot(vecL) ->should be component in direction of normal TODO verify
			double fp = -1*(KsTerm + KdTerm);
			result[0] = myVectorf._mult(lnorm,fp);
			result[1] = myVectorf._mult(lnorm, -fp);

		}//only add force if magnitude of distance vector is not 0
		_p1.vecLOld.set(vecL);
		return result;
	}	
	@Override
	public String toString(){return super.toString() + "\tSpring Constant :  " + String.format("%.2f",constVal1) + " \tDamping Constant : "+String.format("%.2f",constVal2) ;}	
}



//myVector[] result = new myVector[]{new myVector(),new myVector()};
//myVector vecL, v_l;
//switch (force.ftype) {
//	case S_VECTOR:	{
//		result[0] = myVector._mult(_p1.velocity.peekFirst(), force.constVal);//vector here means we derive the force as a particle-dependent vector value, velocity, against some scalar kd 
//		break; }
//	case ATTR: {//attractor, uses two particles, 1st constant, 
//		vecL = new myVector(_p2.position.peekFirst(),_p1.position.peekFirst());//from 2 to 1
//		if (vecL.magn > pa.epsVal) {		//attractor force - constVal (negative) * m1 * m2 * lnorm/ lmag*lmag
//			double m1 = _p1.mass, m2 = _p2.mass;
//			myVector lnorm = myVector._normalize(vecL);//unitlength vector of l
//			double fp = -1 * force.constVal * m1 * m2 / (vecL.sqMagn);
//			result[0] = myVector._mult(lnorm,fp);			
//			result[1] = myVector._mult(lnorm, -fp);
//		}//only add force if magnitude of distance vector is not 0
//		break; }
//	case REPL: {//repulsive force, uses two particles, 1st constant, opposite sign as attractor 
//		vecL = new myVector(_p2.position.peekFirst(),_p1.position.peekFirst());//from 2 to 1
//		if (vecL.magn > pa.epsVal) {		//repulsive force -> constVal * m1 * m2 * lnorm/ lmag*lmag
//			double m1 = _p1.mass, m2 = _p2.mass;
//			myVector lnorm = myVector._normalize(vecL);//unitlength vector of l
//			double fp = -1 * force.constVal * m1 * m2 / (vecL.sqMagn);
//			result[0] = myVector._mult(lnorm,-fp);
//			result[1] = myVector._mult(lnorm,fp);
//		}//only add force if magnitude of distance vector is not 0
//
//		break; }
//	case DAMPSPRING:{//damped spring - not sure if going to use, but what the hey - dependent on old length (need ldot vector)
//		vecL = new myVector(_p2.position.peekFirst(),_p1.position.peekFirst());
//		if (vecL.magn > pa.epsVal) {		//spring with damping force
//			myVector lnorm = myVector._normalize(vecL);//unitlength vector of l
//			myVector lprime = myVector._sub(vecL, myVector._sub(_p1.oldPos.peekFirst(), _p2.oldPos.peekFirst()));		//lprime - time derivative, subtract old length vector from new length vector ?
//			double KsTerm = force.constVal * (vecL.magn - d);
//			double KdTerm = force.constVal2 * (lprime._dot(vecL));
//			double fp = -1 * (KsTerm + KdTerm);
//			result[0] = myVector._mult(lnorm,fp);
//			result[1] = myVector._mult(lnorm, -fp);
//		}//only add force if magnitude of distance vector is not 0
//		break; }
//	case DSPR_THETABAR:{//damped spring to represent ankle
//		vecL = new myVector(_p1.initVel,_p1.velocity.peekFirst());
//		v_l = new myVector(_p2.initPos,_p1.position.peekFirst());
//		if (vecL.magn > pa.epsVal) {		//spring with damping force
//			myVector lnorm = myVector._normalize(vecL);//unitlength vector of l
//			//Eigen::Vector3d lprime = l - (_p1->position[1] - _p2->position[1]);		//lprime - time derivative, subtract old length vector from new length vector ?
//			double KpTerm = force.constVal * (vecL.magn - d);
//			double KdTerm = force.constVal2 * (v_l._dot(vecL));
//			double fp = -1 * (KpTerm + KdTerm);
//			result[0] = myVector._mult(lnorm,fp);
//			result[1] = myVector._mult(lnorm, -fp);
//		}//only add force if magnitude of distance vector is not 0
//		break; }
//	default: {	break; }
//
//}//switch
//return result;

