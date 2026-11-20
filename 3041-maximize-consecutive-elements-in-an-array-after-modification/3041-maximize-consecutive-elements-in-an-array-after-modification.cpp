class Solution {
public:
    int maxSelectedElements(vector<int>& nums) {
        sort(begin(nums),end(nums));
        int elem = nums.back();
        vector<int> count(elem+2,0);
        count[nums[0]]++;
        count[nums[0]+1]++;
        int res = 1;
        for(int i = 1 ; i < nums.size(); i++){
            count[nums[i]+1]=max(max(count[nums[i]]+1,1),count[nums[i]+1]);
            count[nums[i]] = max(max(count[nums[i]-1]+1,1),count[nums[i]]);
            res = max(count[nums[i]],max(res,count[nums[i]+1]));
            cout<<nums[i]<<" "<<max(count[nums[i]],count[nums[i]+1])<<'\n';
        }
        return res;
    }
};